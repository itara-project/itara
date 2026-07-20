package io.itara.agent;

import io.itara.agent.config.ComponentNode;
import io.itara.agent.config.ConfigLoader;
import io.itara.agent.config.ConnectionEntry;
import io.itara.agent.config.Node;
import io.itara.agent.config.NodeKind;
import io.itara.agent.config.VirtualNode;
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
import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.TransportConfig;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
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
        // The system classloader — the hub. It loads all shared artifacts
        // (Itara internals, API jars, event contract jars) and is the parent
        // of every per-component classloader in isolated mode. Captured once,
        // before itaraClassLoader potentially wraps it.
        ClassLoader systemClassLoader = Thread.currentThread().getContextClassLoader();

        // Build the Itara classloader — child-first, loads from itara.lib.dir.
        // For Itara's own plugins (transports, serializers, ...) only —
        // never for component isolation. Falls back to systemClassLoader
        // if the property is not set.
        ClassLoader itaraClassLoader = ItaraClassLoader.build(
                Thread.currentThread().getContextClassLoader());
        ItaraRegistry registry = ItaraRegistry.instance();
        TransportRegistry transportRegistry = TransportRegistry.instance();
        SerializerRegistry serializerRegistry = SerializerRegistry.instance();

        // ── SPIKE: classloader isolation ────────────────────────────────────
        // One URLClassLoader per subdirectory of the components root, parent
        // = systemClassLoader (not itaraClassLoader). Default URLClassLoader
        // delegation is already parent-first — no custom subclass needed.
        // Isolated mode: the directory exists and has at least one subdirectory.
        // Shared mode: it doesn't — componentClassLoaders stays empty and
        // everything below behaves exactly as it does today.
        String componentsDirPath = System.getenv("ITARA_COMPONENTS_DIR");
        File componentsDir = new File(componentsDirPath != null ? componentsDirPath : "lib/components");
        Map<String, ClassLoader> componentClassLoaders = new HashMap<>();
        if (componentsDir.exists() && componentsDir.isDirectory()) {
            File[] subDirs = componentsDir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    File[] jars = subDir.listFiles((d, name) -> name.endsWith(".jar"));
                    if (jars == null || jars.length == 0) continue;
                    URL[] urls = new URL[jars.length];
                    for (int i = 0; i < jars.length; i++) {
                        urls[i] = jars[i].toURI().toURL();
                    }
                    URLClassLoader componentCl = new URLClassLoader(urls, systemClassLoader);
                    componentClassLoaders.put(subDir.getName(), componentCl);
                    log.info("[Itara][SPIKE] created component classloader dir=" + subDir.getName()
                            + " jars=" + jars.length);
                }
            }
        }
        boolean isolatedMode = !componentClassLoaders.isEmpty();
        log.info("[Itara][SPIKE] isolation mode=" + (isolatedMode ? "isolated" : "shared"));

        // ── Step 1: Load wiring config ─────────────────────────────────────
        log.fine("[Itara] loading wiring config path=" + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Build metadata index from .itara files ──────────────────
        log.fine("[Itara] building metadata index dir=" + System.getProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY));
        ItaraMetadataIndex.instance().build();

        // ── Step 3: Scan for contracts (@ComponentInterface and @EventContractInterface) ───────────────
        log.fine("[Itara] scanning classpath for component contracts");
        Map<String, Class<?>> contracts = ContractScanner.scan(itaraClassLoader); //TODO: review classloader usage here
        if (contracts.isEmpty()) {
            log.warning("[Itara] no component contracts found — check that API jars are on the classpath");
        }
        Map<String, Class<?>> eventContracts = EventContractScanner.scan(itaraClassLoader); //TODO: review classloader usage here
        if (!eventContracts.isEmpty()) {
            log.fine("[Itara] found event-contracts count=" + eventContracts.size());
            contracts.putAll(eventContracts);
        }

        // ── Step 4: Scan for activators (META-INF/itara/activator) ─────────
        log.fine("[Itara] scanning for activator descriptors");
        Map<String, ActivatedComponent> activators;
        Map<String, ClassLoader> activatorClassLoaders = new HashMap<>();
        if (isolatedMode) {
            activators = new HashMap<>();
            for (Map.Entry<String, ClassLoader> entry : componentClassLoaders.entrySet()) {
                Map<String, ActivatedComponent> found = ActivatorScanner.scan(entry.getValue(), config);
                for (String componentId : found.keySet()) {
                    activatorClassLoaders.put(componentId, entry.getValue());
                }
                activators.putAll(found);
            }
        } else {
            activators = ActivatorScanner.scan(itaraClassLoader, config);
        }
     /*   log.fine("[Itara] scanning for activator descriptors");
        Map<String, ActivatedComponent> activators = ActivatorScanner.scan(itaraClassLoader, config); //TODO: review classloader usage here
*/
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
        ReconstructibleExceptionFactoryLoader.load(itaraClassLoader); //TODO: review classloader usage here

        // ── Step 8: Initialize ObservabilityFacade ─────────────────────────
        ObservabilityFacade.initialize();

        // ── Step 9: Register activators for local components ───────────────
        if (config.getNodes() != null) {
            for (ComponentNode node : config.componentNodes()) {
                ActivatedComponent activated = activators.get(node.getComponent());

                if (activated != null) {
                    ClassLoader componentCl = activatorClassLoaders.get(node.getComponent());
                    registry.registerActivator(
                            node.getComponent(),
                            activated.getActivatorClass(),
                            contracts.get(node.getComponent()),
                            componentCl != null ? componentCl : itaraClassLoader);

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
                if (conn.isDirect()) {
                    // Colocated — factory handles decoration on first get()
                    log.fine("[Itara] connection wired type=direct from=" + conn.getFrom()
                            + " to=" + conn.getTo());
                    continue;
                }

                // All non-direct connections go through the transport registry
                TransportConfig rawConfig = buildTransportConfig(conn, config);
                String transportId = conn.getTransport().getId();
                ItaraTransportConfig transportConfig = transportRegistry.parseConfig(transportId, rawConfig);
                ItaraTransport transport = transportRegistry.getOrCreate(transportId, transportConfig);
                ItaraSerializer serializer = serializerRegistry.get(conn.getSerializer());

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
                    if (fromNode != null && fromNode.getKind() == NodeKind.VIRTUAL) {
                        componentId = fromNode.contractIdentifier();
                    }

                    DispatchHandler dispatcher = new ItaraDispatcher(
                            componentId, transportId, serializer, registry, pattern);
                    transport.registerListener(componentId, transportConfig, dispatcher);

                    log.info("[Itara] connection established id=" + transportId
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
                            new ItaraProxyHandler(contractId, serializer, transport, transportId,
                                    transportConfig, pattern, failureSemantics, apiMetadata, exceptionFactory)
                    );
                    registry.preRegister(contractId, proxy);

                    log.info("[Itara] connection established id=" + conn.getTransport().getId()
                            + " direction=outbound"
                            + " from=" + conn.getFrom()
                            + " to=" + conn.getTo()
                            + " pattern=" + pattern);
                }
            }
        }

        // ── Step 11: Start transports ────────────────────────────────────────────
        // All listeners have been registered. Transports now have the full picture
        // and can make grouping and resource allocation decisions (one server per
        // port, one consumer per group, etc.).
        transportRegistry.startAll();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Itara] stopping all transports");
            transportRegistry.stopAll();
        }));
    }

    /**
     * Builds a TransportConfig for a connection.
     *
     * The params map comes from the transport block in the wiring config.
     * The agent injects the virtual node topic address on top — this is
     * the one topology fact the transport cannot know from params alone.
     */
    private static TransportConfig buildTransportConfig(ConnectionEntry conn, WiringConfig config) {
        String virtualNodeAddress = config.findVirtualNode(conn.getFrom())
                .or(() -> config.findVirtualNode(conn.getTo()))
                .map(VirtualNode::getAddress)
                .orElse(null);

        return TransportConfig.builder()
                .handleTimeout(conn.getTransport().isHandleTimeout())
                .params(conn.getTransport().getParams())
                .virtualNodeAddress(virtualNodeAddress)
                .build();
    }
}
