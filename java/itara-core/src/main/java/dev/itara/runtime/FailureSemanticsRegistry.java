package dev.itara.runtime;

import dev.itara.spi.failuresemantics.FailureSemanticsConfig;
import dev.itara.spi.failuresemantics.ItaraFailureSemantics;
import dev.itara.spi.failuresemantics.ItaraFailureSemanticsFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available failure semantics factories.
 *
 * <p>Stores one {@link ItaraFailureSemanticsFactory} per type identifier.
 * Populated by the agent at startup — first by registering the built-in
 * noop factory directly, then by the FailureSemanticsLoader which discovers
 * plugin factories from META-INF/itara/failure-semantics on the classpath.
 *
 * <p>At connection wiring time, the agent calls {@link #create} to produce
 * a per-connection {@link ItaraFailureSemantics} instance. Factories are
 * shared; strategy instances are per-connection.
 */
public class FailureSemanticsRegistry {

    private static final Logger log = Logger.getLogger(FailureSemanticsRegistry.class.getName());

    private static final FailureSemanticsRegistry INSTANCE = new FailureSemanticsRegistry();

    private final Map<String, ItaraFailureSemanticsFactory> factories = new ConcurrentHashMap<>();

    private FailureSemanticsRegistry() {}

    /** @return the singleton registry instance */
    public static FailureSemanticsRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register a failure semantics factory.
     * Called by the agent at startup before any connections are processed.
     */
    public void register(ItaraFailureSemanticsFactory factory) {
        factories.put(factory.type().toLowerCase(), factory);
        log.fine("[Itara] registered failure-semantics factory type=" + factory.type()
                + " class=" + factory.getClass().getName());
    }

    /**
     * Create a per-connection {@link ItaraFailureSemantics} instance for
     * the given type identifier and connection config.
     *
     * @throws IllegalStateException if no factory is registered for the type
     * @throws Exception if the factory rejects the config as invalid
     */
    public ItaraFailureSemantics create(String type, FailureSemanticsConfig config)
            throws Exception {
        ItaraFailureSemanticsFactory factory = factories.get(type.toLowerCase());
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No failure semantics factory registered for type '"
                            + type + "'. Available types: " + factories.keySet());
        }
        return factory.create(config);
    }

    /**
     * Returns true if a factory is registered for the given type.
     */
    public boolean has(String type) {
        return factories.containsKey(type.toLowerCase());
    }
}
