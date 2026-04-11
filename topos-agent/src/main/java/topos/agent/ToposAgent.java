package topos.agent;

import topos.agent.config.ConfigLoader;
import topos.agent.config.WiringConfig;
import topos.agent.proxy.RemoteProxyFactory;
import topos.agent.proxy.ToposHttpServer;
import topos.api.ToposActivator;
import topos.runtime.ToposRegistry;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * The Topos Java agent.
 *
 * Entry point: premain(), called by the JVM before the application's
 * main method. Wires the component registry based on the config slice,
 * then hands control to the application.
 *
 * Startup sequence:
 *   1. Load the per-JVM wiring config slice
 *   2. Scan the classpath for @ComponentInterface contracts
 *   3. Scan META-INF/topos/activator files for local activator classes
 *   4. For each connection in the config:
 *        - direct:  register the activator in the registry
 *        - http (inbound): start the HTTP server on the configured port
 *        - http (outbound): create a proxy and pre-register it in the registry
 *   5. Hand control to the application (main method runs normally)
 *
 * JVM arguments:
 *   -javaagent:/path/to/topos-agent.jar
 *   -Dtopos.config=/path/to/wiring-slice.yaml
 *
 * The config path is the only argument the orchestrator needs to pass.
 * Everything else is discovered from the classpath and the config file.
 */
public class ToposAgent {

    /**
     * Called by the JVM before main(). The Instrumentation instance is
     * available for future use — bytecode transformation for callsite
     * patching will use it when ByteBuddy's agent mode is enabled.
     * Currently the agent uses ByteBuddy's subclassing mode to generate
     * remote proxies, which does not require instrumentation.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[Topos] Agent starting...");

        // Install the class transformer FIRST, before any contract class loads.
        // This ensures @ComponentInterface classes get a no-arg constructor
        // patched in if they don't already have one, before RemoteProxyFactory
        // tries to subclass them.
        ContractClassTransformer.install(instrumentation);

        try {
            setup(instrumentation);
        } catch (Exception e) {
            System.err.println("[Topos] FATAL: Agent failed to initialize: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("[Topos] Agent ready. Handing control to application.");
    }

    private static void setup(Instrumentation instrumentation) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        ToposRegistry registry = ToposRegistry.instance();

        // ── Step 1: Load config ────────────────────────────────────────────
        System.out.println("[Topos] Loading wiring config from: "
                + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Scan for contracts (@ComponentInterface) ───────────────
        System.out.println("[Topos] Scanning classpath for component contracts...");
        Map<String, Class<?>> contracts = ContractScanner.scan(classLoader);

        if (contracts.isEmpty()) {
            System.out.println("[Topos] WARNING: No @ComponentInterface classes found "
                    + "on the classpath. Check that interface jars are included.");
        }

        // ── Step 3: Scan for activators (META-INF/topos/activator) ─────────
        System.out.println("[Topos] Scanning for activator descriptors...");
        Map<String, Class<? extends ToposActivator<?>>> activators =
                ActivatorScanner.scan(classLoader);

        // ── Step 4: Register activators for local components ───────────────
        // All components listed in the config with an activator entry are local.
        // Register them so the registry can activate them on demand.
        if (config.getComponents() != null) {
            for (WiringConfig.ComponentEntry entry : config.getComponents()) {
                if (entry.getActivator() != null && !entry.getActivator().isBlank()) {
                    // Activator class name is explicit in config — use it directly
                    @SuppressWarnings("unchecked")
                    Class<? extends ToposActivator<?>> activatorClass =
                            (Class<? extends ToposActivator<?>>) classLoader
                                    .loadClass(entry.getActivator());
                    registry.registerActivator(entry.getId(), activatorClass);
                } else {
                    // Fall back to scanned activators from META-INF
                    Class<? extends ToposActivator<?>> scanned = activators.get(entry.getId());
                    if (scanned != null) {
                        registry.registerActivator(entry.getId(), scanned);
                    }
                    // If neither is present, the component will fail at runtime when requested.
                    // This is intentional: we report the error at the point of use.
                }
            }
        }

        // ── Step 5: Process connections ────────────────────────────────────
        Integer httpServerPort = null;

        if (config.getConnections() != null) {
            for (WiringConfig.ConnectionEntry conn : config.getConnections()) {

                if (conn.isDirect()) {
                    // Direct connection: both components are in this JVM.
                    // Nothing to do here — the activators are already registered.
                    // When the caller's activator calls registry.get(toId),
                    // it will trigger the callee's activator recursively.
                    System.out.println("[Topos] Connection: " + conn.getFrom()
                            + " -> " + conn.getTo() + " [direct]");

                } else if (conn.isHttp()) {

                    if (conn.isExternal()) {
                        // External inbound: this JVM needs an HTTP server
                        // so external callers can reach the 'to' component.
                        // All inbound connections share one server port.
                        if (httpServerPort == null) {
                            httpServerPort = conn.getPort() > 0 ? conn.getPort() : 8080;
                        }
                        System.out.println("[Topos] Connection: [external] -> "
                                + conn.getTo() + " [http, port=" + httpServerPort + "]");

                    } else if (conn.getHost() != null && !conn.getHost().isBlank()) {
                        // Outbound HTTP: this JVM calls a remote component.
                        // Create a proxy and pre-register it in the registry.
                        Class<?> contractClass = contracts.get(conn.getTo());
                        if (contractClass == null) {
                            throw new IllegalStateException(
                                    "[Topos] Cannot create HTTP proxy for '" + conn.getTo()
                                    + "': no @ComponentInterface with that id found "
                                    + "on the classpath. Is the interface jar included?");
                        }

                        Object proxy = RemoteProxyFactory.createHttpProxy(
                                contractClass,
                                conn.getTo(),
                                conn.getHost(),
                                conn.getPort(),
                                classLoader
                        );

                        registry.preRegister(conn.getTo(), proxy);
                        System.out.println("[Topos] Connection: " + conn.getFrom()
                                + " -> " + conn.getTo()
                                + " [http -> " + conn.getHost() + ":" + conn.getPort() + "]");

                    } else {
                        // Inbound from another Topos component (not external, not outbound)
                        // This JVM is the callee. It needs an HTTP server.
                        if (httpServerPort == null) {
                            httpServerPort = conn.getPort() > 0 ? conn.getPort() : 8080;
                        }
                        System.out.println("[Topos] Connection: " + conn.getFrom()
                                + " -> " + conn.getTo()
                                + " [http inbound, port=" + httpServerPort + "]");
                    }
                }
            }
        }

        // ── Step 6: Start HTTP server if needed ────────────────────────────
        if (httpServerPort != null) {
            ToposHttpServer httpServer = new ToposHttpServer(httpServerPort, registry);
            httpServer.start();

            // Shut down gracefully when the JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[Topos] Stopping HTTP server...");
                httpServer.stop();
            }));
        }
    }
}
