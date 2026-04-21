package io.itara.agent;

import io.itara.agent.config.ConfigLoader;
import io.itara.agent.config.WiringConfig;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObserverRegistry;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.ItaraTransport;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Itara Java agent.
 *
 * Startup sequence:
 *   1. Load wiring config
 *   2. Scan classpath for @ComponentInterface contracts
 *   3. Scan META-INF/itara/activator for local activator classes
 *   4. Load META-INF/itara/transport — discover available transport impls
 *   5. Load META-INF/itara/observer — discover available observer impls
 *   6. Register ComponentFactory — activates and wraps instances in
 *      observability decorator for all four events on direct calls
 *   7. Register activators for local components
 *   8. Process connections:
 *        - direct:   nothing to do, factory handles decoration on first get()
 *        - other:    use TransportRegistry to create proxy or start listener
 *   9. Hand control to the application (main runs normally)
 *
 * JVM arguments:
 *   -javaagent:/path/to/itara-agent.jar
 *   "-Ditara.config=/path/to/wiring-slice.yaml"
 */
public class ItaraAgent {

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[Itara] Agent starting...");

        try {
            setup(instrumentation);
        } catch (Exception e) {
            System.err.println("[Itara] FATAL: Agent failed to initialize: "
                    + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("[Itara] Agent ready. Handing control to application.");
    }

    private static void setup(Instrumentation instrumentation) throws Exception {
        // Build the Itara classloader — child-first, loads from itara.lib.dir
        // Falls back to context classloader if property is not set
        ClassLoader itaraClassLoader = ItaraClassLoader.build(
                Thread.currentThread().getContextClassLoader());
        ItaraRegistry registry = ItaraRegistry.instance();
        TransportRegistry transportRegistry = TransportRegistry.instance();

        // ── Step 1: Load wiring config ─────────────────────────────────────
        System.out.println("[Itara] Loading wiring config from: "
                + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Scan for contracts (@ComponentInterface) ───────────────
        System.out.println("[Itara] Scanning classpath for component contracts...");
        Map<String, Class<?>> contracts = ContractScanner.scan(itaraClassLoader);
        if (contracts.isEmpty()) {
            System.out.println("[Itara] WARNING: No @ComponentInterface classes found. "
                    + "Check that API jars are on the classpath.");
        }

        // ── Step 3: Scan for activators (META-INF/itara/activator) ─────────
        System.out.println("[Itara] Scanning for activator descriptors...");
        Map<String, Class<? extends ItaraActivator<?>>> activators =
                ActivatorScanner.scan(itaraClassLoader);

        // ── Step 4: Load transports (META-INF/itara/transport) ─────────────
        System.out.println("[Itara] Loading transport implementations...");
        TransportLoader.load(itaraClassLoader);

        // ── Step 5: Load observers (META-INF/itara/observer) ───────────────
        System.out.println("[Itara] Loading observer implementations...");
        ObserverLoader.load(itaraClassLoader);

        // ── Step 6: Register ComponentFactory ──────────────────────────────
        // The factory is called lazily by the registry on first component access.
        // It activates the component and wraps it in an observability decorator
        // so all four events fire for every direct (colocated) call.
        final Set<String> transportHandled = new HashSet<>();
        registry.setComponentFactory((activatorClass, componentId, contractClass) -> {
            try {
                ItaraActivator<?> activator =
                        activatorClass.getDeclaredConstructor().newInstance();
                Object instance = activator.activate(registry);

                if (transportHandled.contains(componentId)) {
                    return instance; // transport handles observability — no decorator
                }

                // Wrap in observability decorator only if observers are registered
                // and the contract class is known — no-op overhead if no observers
                if (ObserverRegistry.instance().size() > 0 && contractClass != null) {
                    return ObservabilityDecorator.wrap(
                            instance, componentId, contractClass, itaraClassLoader);
                }
                return instance;

            } catch (Exception e) {
                throw new RuntimeException(
                        "[Itara] Failed to activate component '"
                        + componentId + "': " + e.getMessage(), e);
            }
        });

        // ── Step 7: Register activators for local components ───────────────
        if (config.getComponents() != null) {
            for (WiringConfig.ComponentEntry entry : config.getComponents()) {
                Class<? extends ItaraActivator<?>> activatorClass =
                        activators.get(entry.getId());

                if (activatorClass != null) {
                    registry.registerActivator(
                            entry.getId(),
                            activatorClass,
                            contracts.get(entry.getId()));
                }
            }
        }

        // ── Step 8: Process connections ────────────────────────────────────
        if (config.getConnections() != null) {
            for (WiringConfig.ConnectionEntry conn : config.getConnections()) {
                String type = conn.getType();

                if ("direct".equalsIgnoreCase(type)) {
                    // Colocated — factory handles decoration on first get()
                    System.out.println("[Itara] Connection: "
                            + conn.getFrom() + " -> " + conn.getTo()
                            + " [direct]");
                    continue;
                }

                // All non-direct connections go through the transport registry
                ItaraTransport transport = transportRegistry.get(type);

                // Build properties map from the connection entry
                Map<String, String> props = buildProperties(conn);

                if (isOutbound(conn)) {
                    // This JVM calls a remote component — create a proxy
                    Class<?> contractClass = contracts.get(conn.getTo());
                    if (contractClass == null) {
                        throw new IllegalStateException(
                                "[Itara] Cannot create proxy for '" + conn.getTo()
                                + "': no @ComponentInterface with that id found. "
                                + "Is the API jar on the classpath?");
                    }

                    Object proxy = transport.createProxy(
                            conn.getTo(), contractClass, props, itaraClassLoader);
                    registry.preRegister(conn.getTo(), proxy);

                    System.out.println("[Itara] Connection: "
                            + conn.getFrom() + " -> " + conn.getTo()
                            + " [" + type + " outbound]");

                } else {
                    // This JVM exposes a component — start a listener
                    transport.startListener(conn.getTo(), props, registry);
                    transportHandled.add(conn.getTo()); // mark as handled by transport

                    // Register shutdown hook to stop listener cleanly
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        System.out.println("[Itara] Stopping " + type + " listener...");
                        transport.stopListener();
                    }));

                    System.out.println("[Itara] Connection: "
                            + (conn.isExternal() ? "[external]" : conn.getFrom())
                            + " -> " + conn.getTo()
                            + " [" + type + " inbound]");
                }
            }
        }
    }

    /**
     * Determines if a connection is outbound from this JVM's perspective.
     *
     * Outbound = this JVM is the caller, needs a proxy.
     * Inbound  = this JVM is the callee, needs a listener.
     *
     * A connection is outbound if:
     *   - it has a non-empty 'from' (not external entry point)
     *   - it has a host specified (the remote host to call)
     *
     * A connection is inbound if:
     *   - it has no host (this JVM IS the host)
     *   - or it is an external entry point (from is empty)
     */
    private static boolean isOutbound(WiringConfig.ConnectionEntry conn) {
        return !conn.isExternal()
                && conn.getHost() != null
                && !conn.getHost().isBlank();
    }

    /**
     * Builds a properties map from a connection entry.
     * Transports receive this map and extract what they need.
     *
     * Standard keys (transports may define their own additional keys):
     *   host     - remote host (outbound connections)
     *   port     - port number
     *   from     - caller component id
     *   to       - callee component id
     */
    private static Map<String, String> buildProperties(WiringConfig.ConnectionEntry conn) {
        Map<String, String> props = new HashMap<>();
        if (conn.getHost() != null)  props.put("host", conn.getHost());
        if (conn.getPort() > 0)      props.put("port", String.valueOf(conn.getPort()));
        if (conn.getFrom() != null)  props.put("from", conn.getFrom());
        if (conn.getTo() != null)    props.put("to", conn.getTo());
        return props;
    }
}
