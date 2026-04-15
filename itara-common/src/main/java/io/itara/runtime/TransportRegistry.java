package io.itara.runtime;

import io.itara.spi.ItaraTransport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available transport implementations.
 *
 * Populated by the agent at startup via TransportLoader, which scans the
 * classpath for META-INF/Itara/transport descriptor files.
 *
 * Components and other framework code look up transports by type string
 * to create proxies or start listeners.
 */
public class TransportRegistry {

    private static final TransportRegistry INSTANCE = new TransportRegistry();

    private final Map<String, ItaraTransport> transports = new ConcurrentHashMap<>();

    private TransportRegistry() {}

    public static TransportRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register a transport implementation.
     * Called by the agent during startup after discovering transport jars.
     */
    public void register(ItaraTransport transport) {
        transports.put(transport.type().toLowerCase(), transport);
        System.out.println("[Itara] Registered transport: " + transport.type()
                + " -> " + transport.getClass().getName());
    }

    /**
     * Look up a transport by type string.
     *
     * @throws IllegalStateException if no transport is registered for the type,
     *         indicating the transport jar is missing from the classpath
     */
    public ItaraTransport get(String type) {
        ItaraTransport transport = transports.get(type.toLowerCase());
        if (transport == null) {
            throw new IllegalStateException(
                    "[Itara] No transport registered for type '" + type + "'. "
                    + "Add the appropriate transport jar to the classpath. "
                    + "Available transports: " + transports.keySet());
        }
        return transport;
    }

    /**
     * Returns true if a transport is registered for the given type.
     */
    public boolean has(String type) {
        return transports.containsKey(type.toLowerCase());
    }
}
