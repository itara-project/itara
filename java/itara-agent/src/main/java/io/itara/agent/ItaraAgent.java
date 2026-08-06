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
import io.itara.runtime.AuthenticationRegistry;
import io.itara.runtime.AuthorizationRegistry;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.FailureSemanticsRegistry;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.ReconstructibleExceptionRegistry;
import io.itara.runtime.SerializerRegistry;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.authentication.AuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.authorization.AuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.SerializerConfig;
import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.TransportConfig;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
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
 *   7d. Load META-INF/itara/authentication— discover available authentication impls
 *   7e. Load META-INF/itara/authorization— discover available authorization impls
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
        // The system classloader — the hub. Loads all shared artifacts
        // (Itara internals, API jars, event contract jars) and is the
        // parent of every per-component classloader in isolated mode.
        // Captured before itaraClassLoader wraps it.
        ClassLoader systemClassLoader = Thread.currentThread().getContextClassLoader();

        // Build the Itara classloader — child-first, loads from itara.lib.dir.
        // For Itara's own plugins (transports, serializers, observers,
        // failure semantics) only — never for scanning shared contracts,
        // component activators, or proxy creation. Falls back to
        // systemClassLoader if the property is not set.
        ClassLoader itaraClassLoader = ItaraClassLoader.build(systemClassLoader);
        ItaraRegistry registry = ItaraRegistry.instance();
        TransportRegistry transportRegistry = TransportRegistry.instance();
        SerializerRegistry serializerRegistry = SerializerRegistry.instance();
        AuthenticationRegistry authenticationRegistry = AuthenticationRegistry.instance();
        AuthorizationRegistry authorizationRegistry = AuthorizationRegistry.instance();

        // ── Step 1: Load wiring config ─────────────────────────────────────
        log.fine("[Itara] loading wiring config path=" + System.getProperty(ConfigLoader.CONFIG_PROPERTY));
        WiringConfig config = ConfigLoader.load();

        // ── Step 2: Build metadata index from .itara files ──────────────────
        log.fine("[Itara] building metadata index dir=" + System.getProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY));
        ItaraMetadataIndex.instance().build();

        // ── Step 3: Scan for contracts (@ComponentInterface and @EventContractInterface) ───────────────
        log.fine("[Itara] scanning classpath for component contracts");
        Map<String, Class<?>> contracts = ContractScanner.scan(systemClassLoader);
        if (contracts.isEmpty()) {
            log.warning("[Itara] no component contracts found — check that API jars are on the classpath");
        }
        Map<String, Class<?>> eventContracts = EventContractScanner.scan(systemClassLoader);
        if (!eventContracts.isEmpty()) {
            log.fine("[Itara] found event-contracts count=" + eventContracts.size());
            contracts.putAll(eventContracts);
        }

        // ── Step 4: Scan for activators (META-INF/itara/activator) ─────────
        // ActivatorScanner owns isolated-vs-shared detection, per-component
        // classloader creation, and verification — see ActivatorScanner.
        log.fine("[Itara] scanning for activator descriptors");
        ActivatorScanner.instance().scan(systemClassLoader, config);

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
        ReconstructibleExceptionFactoryLoader.load(systemClassLoader);

        // ── Step 7d: Load authentication factories (META-INF/itara/authentication)
        log.fine("[Itara] loading authentication factories");
        AuthenticationLoader.load(itaraClassLoader);

        // ── Step 7e: Load authorization factories (META-INF/itara/authorization)
        log.fine("[Itara] loading authorization factories");
        AuthorizationLoader.load(itaraClassLoader);

        // ── Step 8: Initialize ObservabilityFacade ─────────────────────────
        ObservabilityFacade.initialize();

        // ── Step 9: Register activators for local components ───────────────
        // Iterates local nodes only — ActivatorScanner.scan() already
        // guarantees every one of these has an activator and a classloader,
        // so there is no need to guard against a missing entry here.
        for (ComponentNode node : config.getLocalNodes()) {
            String componentId = node.getComponent();
            ActivatedComponent activated = ActivatorScanner.instance().getActivatedComponent(componentId);
            ClassLoader componentClassLoader = ActivatorScanner.instance().getClassLoader(componentId);

            registry.registerActivator(
                    componentId,
                    activated.getActivatorClass(),
                    contracts.get(componentId),
                    componentClassLoader);

            // Register aliases for all event contracts this component
            // implements, as declared in [implemented-event-contracts]
            // in its .itara metadata file.
            // Aliases are registered here — before any listeners start
            // in step 10 — so the registry is ready the moment the
            // first message arrives.
            ItaraMetadataIndex.instance()
                    .lookupByComponentId(componentId)
                    .ifPresent(metadata -> {
                        for (var contract : metadata.getImplementedEventContracts().getContracts()) {
                            registry.registerAlias(contract.getId(), node.getComponent());
                            log.fine("[Itara] registered event-contract-alias id="
                                    + contract.getId()
                                    + " component=" + node.getComponent());
                        }
                    });
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

                SerializerConfig rawSerializerConfig = buildSerializerConfig(conn);
                String serializerId = conn.getSerializer().getId();
                ItaraSerializerConfig serializerConfig = serializerRegistry.parseConfig(serializerId, rawSerializerConfig);
                ItaraSerializer serializer = serializerRegistry.getOrCreate(serializerId, serializerConfig);

                AuthenticationConfig rawAuthenticationConfig = buildAuthenticationConfig(conn);
                ItaraAuthenticationConfig authenticationConfig =
                        authenticationRegistry.parseConfig(conn.getAuthenticationId(), rawAuthenticationConfig);
                ItaraAuthentication authentication =
                        authenticationRegistry.getOrCreate(conn.getAuthenticationId(), authenticationConfig);

                AuthorizationConfig rawAuthorizationConfig = buildAuthorizationConfig(conn);
                ItaraAuthorizationConfig authorizationConfig =
                        authorizationRegistry.parseConfig(conn.getAuthorizationId(), rawAuthorizationConfig);
                ItaraAuthorization authorization =
                        authorizationRegistry.getOrCreate(conn.getAuthorizationId(), authorizationConfig);

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
                            componentId, toNode.getId(), transportId, serializer, serializerConfig, registry, pattern,
                            authentication, authenticationConfig, authorization, authorizationConfig);
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
                                    conn.getFailureSemanticsId(),
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
                            systemClassLoader,
                            new Class<?>[]{ contractClass },
                            new ItaraProxyHandler(contractId, toNode.getId(), serializer, serializerConfig, transport, transportId,
                                    transportConfig, pattern, failureSemantics, authentication, authenticationConfig, apiMetadata,
                                    exceptionFactory)
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

    /**
     * Builds a SerializerConfig for a connection.
     *
     * The wiring config's serializer field is currently a bare id string with
     * no params block — this returns an empty params map as a stopgap until
     * the wiring config gains a serializer.params block mirroring transport's.
     */
    private static SerializerConfig buildSerializerConfig(ConnectionEntry conn) {
        return SerializerConfig.builder()
                .params(conn.getSerializer().getParams())
                .build();
    }

    /**
     * Builds an AuthenticationConfig for a connection.
     *
     * Absent authentication block → empty params, same shape as
     * buildSerializerConfig. ConnectionEntry.getAuthenticationId() already
     * defaults to "noop" regardless of whether the block is present.
     */
    private static AuthenticationConfig buildAuthenticationConfig(ConnectionEntry conn) {
        Map<String, String> params = conn.getAuthentication() != null
                ? conn.getAuthentication().getParams()
                : Collections.emptyMap();
        return AuthenticationConfig.builder().params(params).build();
    }

    /**
     * Builds an AuthorizationConfig for a connection.
     *
     * Absent authorization block → empty params, same shape as
     * buildSerializerConfig. ConnectionEntry.getAuthorizationId() already
     * defaults to "noop" regardless of whether the block is present.
     */
    private static AuthorizationConfig buildAuthorizationConfig(ConnectionEntry conn) {
        Map<String, String> params = conn.getAuthorization() != null
                ? conn.getAuthorization().getParams()
                : Collections.emptyMap();
        return AuthorizationConfig.builder().params(params).build();
    }
}
