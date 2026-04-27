package io.itara.runtime;

import io.itara.spi.ItaraSerializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final SerializerRegistry INSTANCE = new SerializerRegistry();

    private final Map<String, ItaraSerializer> serializers = new ConcurrentHashMap<>();

    private SerializerRegistry() {}

    public static SerializerRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register a serializer implementation.
     * Called by the agent during startup after discovering serializer jars.
     */
    public void register(ItaraSerializer serializer) {
        serializers.put(serializer.type().toLowerCase(), serializer);
        System.out.println("[Itara] Registered serializer: " + serializer.type()
                + " -> " + serializer.getClass().getName());
    }

    /**
     * Look up a serializer by type string.
     *
     * @throws IllegalStateException if no serializer is registered for the type,
     *         indicating the serializer jar is missing from the classpath
     */
    public ItaraSerializer get(String type) {
        ItaraSerializer serializer = serializers.get(type.toLowerCase());
        if (serializer == null) {
            throw new IllegalStateException(
                    "[Itara] No serializer registered for type '" + type + "'. "
                            + "Add the appropriate serializer jar to the classpath. "
                            + "Available serializers: " + serializers.keySet());
        }
        return serializer;
    }

    /**
     * Returns true if a serializer is registered for the given type.
     */
    public boolean has(String type) {
        return serializers.containsKey(type.toLowerCase());
    }
}
