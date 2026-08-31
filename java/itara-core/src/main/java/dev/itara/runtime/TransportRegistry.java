package dev.itara.runtime;

import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.spi.transport.ItaraTransportFactory;
import dev.itara.spi.transport.ItaraTransportGroupingKey;
import dev.itara.spi.transport.TransportConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of transport factories and active transport instances.
 *
 * <p>Populated with factories by TransportLoader at agent startup. Instances
 * are created on demand during connection processing — one instance per
 * unique grouping key per transport type.
 *
 * <p>The registry owns the full instance lifecycle: creation via the factory,
 * start after all listeners are registered, and stop on shutdown.
 */
public class TransportRegistry {

    private static final Logger log = Logger.getLogger(TransportRegistry.class.getName());

    private static final TransportRegistry INSTANCE = new TransportRegistry();

    /** One factory per transport type id. */
    private final Map<String, ItaraTransportFactory> factories = new ConcurrentHashMap<>();

    /** Active instances: type id → (grouping key → instance). */
    private final Map<String, Map<ItaraTransportGroupingKey, ItaraTransport>> instances = new ConcurrentHashMap<>();

    private TransportRegistry() {}

    /**
     * Returns the singleton registry instance.
     *
     * @return the singleton registry instance
     */
    public static TransportRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register a transport factory.
     * Called by TransportLoader during agent startup before any connections
     * are processed.
     *
     * @param factory the factory to register
     */
    public void registerFactory(ItaraTransportFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered transport factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

    /**
     * Parses the raw transport config into a typed, transport-specific config
     * via the registered factory. The returned object carries the grouping key
     * and is passed directly to getOrCreate() — no second parse.
     *
     * @param id      The transport id from the wiring config transport block
     * @param config  The raw transport config for this connection
     * @return        The parsed, typed transport config
     * @throws Exception if no factory is registered for the id, or if parsing fails
     */
    public ItaraTransportConfig parseConfig(String id, TransportConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraTransportFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No transport factory registered for id '" + id + "'. "
                            + "Add the appropriate transport jar to the classpath. "
                            + "Available ids: " + factories.keySet());
        }
        return factory.parseConfig(config);
    }

    /**
     * Returns an existing transport instance for the given parsed config's
     * grouping key, or creates a new one via the factory if none exists.
     *
     * @param id      The transport id from the wiring config transport block
     * @param config  The parsed transport config, as returned by parseConfig()
     * @return        The transport instance responsible for this connection
     * @throws Exception if the factory fails to create a new instance
     */
    public ItaraTransport getOrCreate(String id, ItaraTransportConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraTransportFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No transport factory registered for id '" + id + "'. "
                            + "Available ids: " + factories.keySet());
        }

        Map<ItaraTransportGroupingKey, ItaraTransport> byKey =
                instances.computeIfAbsent(normalizedId, k -> new ConcurrentHashMap<>());

        ItaraTransportGroupingKey groupingKey = config.groupingKey();

        try {
            return byKey.computeIfAbsent(groupingKey, k -> {
                try {
                    ItaraTransport transport = factory.create(config);
                    log.fine("[Itara] created transport instance id=" + id
                            + " key=" + groupingKey
                            + " class=" + transport.getClass().getName());
                    return transport;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw new Exception(
                    "[Itara] Failed to create transport instance id='" + id
                            + "': " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * Start all created transport instances.
     * Called by the agent once after all connections are processed.
     *
     * @throws Exception if any transport instance fails to start
     */
    public void startAll() throws Exception {
        for (Map.Entry<String, Map<ItaraTransportGroupingKey, ItaraTransport>> byId
                : instances.entrySet()) {
            for (Map.Entry<ItaraTransportGroupingKey, ItaraTransport> entry
                    : byId.getValue().entrySet()) {
                log.fine("[Itara] starting transport id=" + byId.getKey()
                        + " key=" + entry.getKey());
                entry.getValue().start();
            }
        }
    }

    /**
     * Stop all created transport instances.
     * Called by the agent's shutdown hook.
     */
    public void stopAll() {
        for (Map.Entry<String, Map<ItaraTransportGroupingKey, ItaraTransport>> byId
                : instances.entrySet()) {
            for (Map.Entry<ItaraTransportGroupingKey, ItaraTransport> entry
                    : byId.getValue().entrySet()) {
                log.fine("[Itara] stopping transport id=" + byId.getKey()
                        + " key=" + entry.getKey());
                entry.getValue().stop();
            }
        }
    }

    /**
     * Resets all registered factories and instances.
     * For testing only — do not call in production code.
     */
    public void reset() {
        factories.clear();
        instances.clear();
    }
}
