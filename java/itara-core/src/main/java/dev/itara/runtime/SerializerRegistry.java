package dev.itara.runtime;

import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerFactory;
import dev.itara.spi.serializer.ItaraSerializerGroupingKey;
import dev.itara.spi.serializer.SerializerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available serializer implementations.
 *
 * <p>Populated by the agent at startup via SerializerLoader, which scans the
 * classpath for META-INF/itara/serializer descriptor files.
 *
 * <p>Components and other framework code look up serializers by type string
 * to serialize and deserialize messages across components.
 */
public class SerializerRegistry {

    private static final Logger log = Logger.getLogger(SerializerRegistry.class.getName());

    private static final SerializerRegistry INSTANCE = new SerializerRegistry();

    /** One factory per serializer id. */
    private final Map<String, ItaraSerializerFactory> factories = new ConcurrentHashMap<>();

    /** Active instances: id → (grouping key → instance). */
    private final Map<String, Map<ItaraSerializerGroupingKey, ItaraSerializer>> instances = new ConcurrentHashMap<>();

    private SerializerRegistry() {}

    /**
     * Returns the singleton registry instance.
     *
     * @return the singleton registry instance
     */
    public static SerializerRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register a serializer factory.
     * Called by SerializerLoader during agent startup before any connections
     * are processed.
     *
     * @param factory the factory to register
     */
    public void registerFactory(ItaraSerializerFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered serializer factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

    /**
     * Parses the raw serializer config into a typed, serializer-specific config
     * via the registered factory. The returned object carries the grouping key
     * and is passed directly to getOrCreate() — no second parse.
     *
     * @param id      The serializer id from the wiring config serializer block
     * @param config  The raw serializer config for this connection
     * @return        The parsed, typed serializer config
     * @throws Exception if no factory is registered for the id, or if parsing fails
     */
    public ItaraSerializerConfig parseConfig(String id, SerializerConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraSerializerFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No serializer factory registered for id '" + id + "'. "
                            + "Add the appropriate serializer jar to the classpath. "
                            + "Available ids: " + factories.keySet());
        }
        return factory.parseConfig(config);
    }

    /**
     * Returns an existing serializer instance for the given parsed config's
     * grouping key, or creates a new one via the factory if none exists.
     *
     * @param id      The serializer id from the wiring config serializer block
     * @param config  The parsed serializer config, as returned by parseConfig()
     * @return        The serializer instance responsible for this connection
     * @throws Exception if the factory fails to create a new instance
     */
    public ItaraSerializer getOrCreate(String id, ItaraSerializerConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraSerializerFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No serializer factory registered for id '" + id + "'. "
                            + "Available ids: " + factories.keySet());
        }

        Map<ItaraSerializerGroupingKey, ItaraSerializer> byKey =
                instances.computeIfAbsent(normalizedId, k -> new ConcurrentHashMap<>());

        ItaraSerializerGroupingKey groupingKey = config.groupingKey();

        try {
            return byKey.computeIfAbsent(groupingKey, k -> {
                try {
                    ItaraSerializer serializer = factory.create(config);
                    log.fine("[Itara] created serializer instance id=" + id
                            + " key=" + groupingKey
                            + " class=" + serializer.getClass().getName());
                    return serializer;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw new Exception(
                    "[Itara] Failed to create serializer instance id='" + id
                            + "': " + e.getCause().getMessage(), e.getCause());
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
