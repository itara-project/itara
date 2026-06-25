package io.itara.agent;

import io.itara.agent.config.ComponentNode;
import io.itara.agent.config.ConfigLoader;
import io.itara.agent.config.ConnectionEntry;
import io.itara.agent.config.Node;
import io.itara.agent.config.NodeKind;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.metadata.ItaraMetadataIndex;
import io.itara.agent.metadata.MetadataFile;
import io.itara.exceptions.ItaraReconstructibleExceptionFactory;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.FailureSemanticsRegistry;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.ReconstructibleExceptionRegistry;
import io.itara.runtime.SerializerRegistry;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;

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
 *   3b. For each local component, read [implemented-event-contracts]
 *       from its .itara metadata and register registry aliases so the
 *       dispatcher can find the implementation by event contract id
 *   4. Scan META-INF/itara/activator for local activator classes,
 *      resolving component identity (id, version, api-version) via the
 *      metadata index built in step 2
 *   5. Load META-INF/itara/serializer — discover available serializer impls
 *   6. Load META-INF/itara/transport — discover available transport impls
 *   7. Load META-INF/itara/observer — discover available observer impls
 *   7b. Load META-INF/itara/failure-semantics — discover available failure
 *       semantics implementations
 *   7c. Load META-INF/itara/exception-factory — discover reconstructible
 *       exception factories from API artifact jars
 *   8. Initialize ObservabilityFacade
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
        log.info("[Itara] agent starting");

        try {
            setup(instrumentation);
        } catch (Exception e) {
            log.severe("[Itara] agent startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        log.info("[Itara] agent ready");
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
        log.fine("[Itara] loading wiring config path=" + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Build metadata index from .itara files ──────────────────
        log.fine("[Itara] building metadata index dir=" + System.getProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY));
        ItaraMetadataIndex.instance().build();

        // ── Step 3: Scan for contracts (@ComponentInterface and @EventContractInterface) ───────────────
        log.fine("[Itara] scanning classpath for component contracts");
        Map<String, Class<?>> contracts = ContractScanner.scan(itaraClassLoader);
        if (contracts.isEmpty()) {
            log.warning("[Itara] no component contracts found — check that API jars are on the classpath");
        }
        Map<String, Class<?>> eventContracts = EventContractScanner.scan(itaraClassLoader);
        if (!eventContracts.isEmpty()) {
            log.fine("[Itara] found event-contracts count=" + eventContracts.size());
            contracts.putAll(eventContracts);
        }

        // ── Step 4: Scan for activators (META-INF/itara/activator) ─────────
        log.fine("[Itara] scanning for activator descriptors");
        Map<String, ActivatedComponent> activators = ActivatorScanner.scan(itaraClassLoader, config);

        // ── Step 5: Load serializers (META-INF/itara/serializer) ─────────────
        log.fine("[Itara] loading serializer implementations");
        SerializerLoader.load(itaraClassLoader);

        // ── Step 6: Load transports (META-INF/itara/transport) ─────────────
        log.fine("[Itara] loading transport implementations");
        TransportLoader.load(itaraClassLoader);

        // ── Step 7: Load observers (META-INF/itara/observer) ───────────────
        log.fine("[Itara] loading observer implementations");
        ObserverLoader.load(itaraClassLoader);

        // ── Step 7b: Load failure semantics (META-INF/itara/failure-semantics)
        log.fine("[Itara] loading failure semantics implementations");
        FailureSemanticsLoader.load(itaraClassLoader);

        // ── Step 7c: Load exception factories (META-INF/itara/exception-factory)
        log.fine("[Itara] loading reconstructible exception factories");
        ReconstructibleExceptionFactoryLoader.load(itaraClassLoader);

        // ── Step 8: Initialize ObservabilityFacade ─────────────────────────
        ObservabilityFacade.initialize();

        // ── Step 9: Register activators for local components ───────────────
        if (config.getNodes() != null) {
            for (ComponentNode node : config.componentNodes()) {
                ActivatedComponent activated = activators.get(node.getComponent());

                if (activated != null) {
                    registry.registerActivator(
                            node.getComponent(),
                            activated.getActivatorClass(),
                            contracts.get(node.getComponent()));

                    // Register aliases for all event contracts this component
                    // implements, as declared in [implemented-event-contracts]
                    // in its .itara metadata file.
                    // Aliases are registered here — before any listeners start
                    // in step 10 — so the registry is ready the moment the
                    // first message arrives.
                    ItaraMetadataIndex.instance()
                            .lookupByComponentId(node.getComponent())
                            .ifPresent(metadata -> {
                                for (var contract : metadata.getImplementedEventContracts().getContracts()) {
                                    registry.registerAlias(
                                            contract.getId(), node.getComponent());
                                    log.fine("[Itara] registered event-contract-alias id="
                                            + contract.getId()
                                            + " component=" + node.getComponent());
                                }
                            });
                }
            }
        }

        // ── Step 10: Process connections ────────────────────────────────────
        if (config.getConnections() != null) {
            for (ConnectionEntry conn : config.getConnections()) {
                String type = conn.getType();

                if (conn.isDirect()) {
                    // Colocated — factory handles decoration on first get()
                    log.fine("[Itara] connection wired type=direct from=" + conn.getFrom()
                            + " to=" + conn.getTo());
                    continue;
                }

                // All non-direct connections go through the transport registry
                ItaraTransport transport = transportRegistry.get(conn.getType());
                ItaraSerializer serializer = serializerRegistry.get(conn.getSerializer());

                // Build properties map from the connection entry
                Map<String, String> props = buildProperties(conn, config);

                Node toNode   = config.findNode(conn.getTo()).orElseThrow();
                Node fromNode = conn.getFrom() != null
                        ? config.findNode(conn.getFrom()).orElse(null)
                        : null;

                ExchangePattern pattern = (toNode.getKind() == NodeKind.VIRTUAL
                        || (fromNode != null && fromNode.getKind() == NodeKind.VIRTUAL))
                        ? ExchangePattern.FIRE_AND_FORGET
                        : ExchangePattern.REQUEST_REPLY;

                boolean toIsLocal = config.getLocalNodeIds().contains(conn.getTo());
                boolean fromIsLocal = conn.getFrom() != null
                        && config.getLocalNodeIds().contains(conn.getFrom());

                if (toIsLocal) {
                    // Inbound — wire a dispatcher regardless of node type
                    // ExchangePattern handles the virtual/component distinction
                    String componentId = switch (toNode.getKind()) {
                        case COMPONENT -> ((ComponentNode) toNode).getComponent();
                        case VIRTUAL   -> throw new IllegalStateException(
                                "[Itara] Virtual node '" + toNode.getId()
                                        + "' cannot be an inbound target.");
                    };

                    DispatchHandler dispatcher = new ItaraDispatcher(
                            componentId, conn.getType(), serializer, registry, pattern);
                    transport.startListener(componentId, props, dispatcher);

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("[Itara] stopping listener type=" + conn.getType()
                                + " component=" + componentId);
                        transport.stopListener();
                    }));

                    log.info("[Itara] connection established type=" + conn.getType()
                            + " direction=inbound"
                            + " from=" + (conn.isExternal() ? "external" : conn.getFrom())
                            + " to=" + conn.getTo()
                            + " pattern=" + pattern);

                } else if (fromIsLocal) {
                    // Outbound — wire a proxy regardless of node type
                    String contractId = toNode.contractIdentifier();
                    Class<?> contractClass = contracts.get(contractId);
                    if (contractClass == null) {
                        throw new IllegalStateException(
                                "[Itara] Cannot create proxy for '" + conn.getTo()
                                        + "': no contract class found for '" + contractId + "'. "
                                        + "Is the API or events jar on the classpath?");
                    }

                    ItaraFailureSemantics failureSemantics =
                            FailureSemanticsRegistry.instance().create(
                                    conn.getFailureSemanticsType(),
                                    conn.getFailureSemanticsConfig());

                    MetadataFile apiMetadata = ItaraMetadataIndex.instance()
                            .lookupByContractId(contractId)
                            .orElse(null);
                    if (apiMetadata == null) {
                        log.warning("[Itara] no metadata found for API artifact contract=" + contractId
                                + " — all methods will be treated as idempotent");
                    }

                    ItaraReconstructibleExceptionFactory exceptionFactory =
                            ReconstructibleExceptionRegistry.instance()
                                    .get(contractId)
                                    .orElse(null);

                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                            itaraClassLoader,
                            new Class<?>[]{ contractClass },
                            new ItaraProxyHandler(contractId, serializer, transport,
                                    props, pattern, failureSemantics, apiMetadata, exceptionFactory)
                    );
                    registry.preRegister(contractId, proxy);

                    log.info("[Itara] connection established type=" + conn.getType()
                            + " direction=outbound"
                            + " from=" + conn.getFrom()
                            + " to=" + conn.getTo()
                            + " pattern=" + pattern);
                }
            }
        }
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

        // from: resolve component id for component nodes, skip for virtual nodes
        if (conn.getFrom() != null && !conn.getFrom().isBlank()
                && !config.isVirtualNode(conn.getFrom())) {
            props.put("from", config.getComponentOfNodeId(conn.getFrom()));
        }

        // to: resolve component id for component nodes, skip for virtual nodes
        if (conn.getTo() != null && !config.isVirtualNode(conn.getTo())) {
            props.put("to", config.getComponentOfNodeId(conn.getTo()));
        }

        // Kafka-specific: topic address from the virtual node, consumer group from the connection
        config.findVirtualNode(conn.getFrom()).ifPresent(vn -> props.put("topic", vn.getAddress()));
        config.findVirtualNode(conn.getTo()).ifPresent(vn -> props.put("topic", vn.getAddress()));
        if (conn.getConsumerGroup() != null) props.put("consumerGroup", conn.getConsumerGroup());
        if (conn.getBootstrapServers() != null) props.put("bootstrapServers", conn.getBootstrapServers());

        // Future: additional connection properties from the YAML will be added here
        return props;
    }
}
