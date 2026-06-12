package io.itara.agent;

import io.itara.agent.config.NodeEntry;
import io.itara.agent.config.ConfigLoader;
import io.itara.agent.config.ConnectionEntry;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.metadata.ItaraMetadataIndex;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.OtelBridge;
import io.itara.runtime.SerializerRegistry;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The Itara Java agent.
 *
 * Startup sequence:
 *   1. Load wiring config
 *   2. Build metadata index from .itara files (itara.metadata.dir)
 *   3. Scan classpath for @ComponentInterface contracts
 *   4. Scan META-INF/itara/activator for local activator classes,
 *      resolving component identity (id, version, api-version) via the
 *      metadata index built in step 2
 *   5. Load META-INF/itara/serializer — discover available serializer impls
 *   6. Load META-INF/itara/transport — discover available transport impls
 *   7. Load META-INF/itara/otel-bridge — discover the OTel bridge impl
 *   8. Load META-INF/itara/observer — discover available observer impls
 *   9. Register ComponentFactory — activates and wraps instances in
 *      observability decorator for all four events on direct calls
 *  10. Register activators for local components
 *  11. Process connections:
 *        - direct:   nothing to do, factory handles decoration on first get()
 *        - other:    use TransportRegistry to create proxy or start listener
 *  12. Hand control to the application (main runs normally)
 *
 * JVM arguments:
 *   -javaagent:/path/to/itara-agent.jar
 *   "-Ditara.config=/path/to/wiring-slice.yaml"
 *   "-Ditara.metadata.dir=/path/to/.itara"
 */
public class ItaraAgent {

    private static final Logger log = Logger.getLogger(ItaraAgent.class.getName());

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("[Itara] Agent starting...");

        try {
            setup(instrumentation);
        } catch (Exception e) {
            log.severe("[Itara] FATAL: Agent failed to initialize: "
                    + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        log.info("[Itara] Agent ready. Handing control to application.");
    }

    private static void setup(Instrumentation instrumentation) throws Exception {
        // Build the Itara classloader — child-first, loads from itara.lib.dir
        // Falls back to context classloader if property is not set
        ClassLoader itaraClassLoader = ItaraClassLoader.build(
                Thread.currentThread().getContextClassLoader());
        ItaraRegistry registry = ItaraRegistry.instance();
        TransportRegistry transportRegistry = TransportRegistry.instance();
        SerializerRegistry serializerRegistry = SerializerRegistry.instance();

        // ── Step 1: Load wiring config ─────────────────────────────────────
        log.info("[Itara] Loading wiring config from: " + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Build metadata index from .itara files ──────────────────
        log.info("[Itara] Building metadata index from: " + System.getProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY));
        ItaraMetadataIndex.instance().build();

        // ── Step 3: Scan for contracts (@ComponentInterface) ───────────────
        log.info("[Itara] Scanning classpath for component contracts...");
        Map<String, Class<?>> contracts = ContractScanner.scan(itaraClassLoader);
        if (contracts.isEmpty()) {
            log.warning("[Itara] WARNING: No @ComponentInterface classes found. "
                    + "Check that API jars are on the classpath.");
        }

        // ── Step 4: Scan for activators (META-INF/itara/activator) ─────────
        log.info("[Itara] Scanning for activator descriptors...");
        Map<String, ActivatedComponent> activators = ActivatorScanner.scan(itaraClassLoader, config);

        // ── Step 5: Load serializers (META-INF/itara/serializer) ─────────────
        log.info("[Itara] Loading serializer implementations...");
        SerializerLoader.load(itaraClassLoader);

        // ── Step 6: Load transports (META-INF/itara/transport) ─────────────
        log.info("[Itara] Loading transport implementations...");
        TransportLoader.load(itaraClassLoader);

        // ── Step 7: Load OTEL bridge (META-INF/itara/otel-bridge) ─────────────
        log.info("[Itara] Loading OTel bridge implementations...");
        final OtelBridge otelBridge = OtelBridgeLoader.load(itaraClassLoader);

        // ── Step 8: Load observers (META-INF/itara/observer) ───────────────
        log.info("[Itara] Loading observer implementations...");
        ObserverLoader.load(itaraClassLoader);

        ObservabilityFacade.initialize(otelBridge);

        // ── Step 9: Register activators for local components ───────────────
        if (config.getNodes() != null) {
            for (NodeEntry entry : config.getNodes()) {
                ActivatedComponent activated = activators.get(entry.getComponent());

                if (activated != null) {
                    registry.registerActivator(
                            entry.getComponent(),
                            activated.getActivatorClass(),
                            contracts.get(entry.getComponent()));
                }
            }
        }

        // ── Step 10: Process connections ────────────────────────────────────
        if (config.getConnections() != null) {
            for (ConnectionEntry conn : config.getConnections()) {
                String type = conn.getType();

                if ("direct".equalsIgnoreCase(type)) {
                    // Colocated — factory handles decoration on first get()
                    log.info("[Itara] Connection: "
                            + conn.getFrom() + " -> " + conn.getTo()
                            + " [direct]");
                    continue;
                }

                // All non-direct connections go through the transport registry
                ItaraTransport transport = transportRegistry.get(type);
                ItaraSerializer serializer = serializerRegistry.get(conn.getSerializer());

                // Build properties map from the connection entry
                Map<String, String> props = buildProperties(conn, config);

                if (isOutbound(conn, config)) {
                    // Outbound — agent owns the proxy, transport is just a byte carrier
                    String remoteComponentId = config.getComponentOfNodeId(conn.getTo());
                    Class<?> contractClass = contracts.get(remoteComponentId);
                    if (contractClass == null) {
                        throw new IllegalStateException(
                                "[Itara] Cannot create proxy for '" + conn.getTo()
                                + "': no @ComponentInterface with that id found. "
                                + "Is the API jar on the classpath?");
                    }

                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                            itaraClassLoader,
                            new Class<?>[]{ contractClass },
                            new ItaraProxyHandler(remoteComponentId, serializer, transport, props)
                    );
                    registry.preRegister(remoteComponentId, proxy);

                    log.info("[Itara] Connection: " + conn.getFrom() + " -> " + conn.getTo()
                            + " [" + type + " outbound]");

                } else {
                    // Inbound — agent owns the dispatcher, transport delivers bytes to it
                    String localComponentId = config.getComponentOfNodeId(conn.getTo());

                    DispatchHandler dispatcher = new ItaraDispatcher(
                            localComponentId, type, serializer, registry
                    );
                    transport.startListener(localComponentId, props, dispatcher);

                    // Register shutdown hook to stop listener cleanly
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("[Itara] Stopping " + type + " listener...");
                        transport.stopListener();
                    }));

                    log.info("[Itara] Connection: "
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
     * A connection is outbound if a local node is marked as from in the connection
     *
     * A connection is inbound if it's not outbound.
     */
    private static boolean isOutbound(ConnectionEntry conn, WiringConfig config) {
        return config.getLocalNodeIds().contains(conn.getFrom());
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
    private static Map<String, String> buildProperties(ConnectionEntry conn, WiringConfig config) {
        Map<String, String> props = new HashMap<>();
        if (conn.getHost() != null)  props.put("host", conn.getHost());
        if (conn.getPort() > 0)      props.put("port", String.valueOf(conn.getPort()));
        if (conn.getFrom() != null)  props.put("from", config.getComponentOfNodeId(conn.getFrom()));
        if (conn.getTo() != null)    props.put("to", config.getComponentOfNodeId(conn.getTo()));
        // Future: additional connection properties from the YAML will be added here
        return props;
    }
}
