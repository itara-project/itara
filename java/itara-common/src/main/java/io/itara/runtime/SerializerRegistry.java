package io.itara.runtime;

import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerFactory;
import io.itara.spi.serializer.ItaraSerializerGroupingKey;
import io.itara.spi.serializer.SerializerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available serializer implementations.
 *
 * Populated by the agent at startup via SerializerLoader, which scans the
 * classpath for META-INF/Itara/serializer descriptor files.
 *
 * Components and other framework code look up serializers by type string
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

    public static SerializerRegistry instance() {
        return INSTANCE;
    }

    public void registerFactory(ItaraSerializerFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered serializer factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

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
