package io.itara.runtime;

import io.itara.spi.authorization.AuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorizationFactory;
import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available authorization implementations.
 *
 * Populated by the agent at startup via AuthorizationLoader, which
 * registers the built-in noop factory directly, then scans the classpath
 * for META-INF/itara/authorization descriptor files.
 *
 * The connection-processing loop looks up authorization by id to wire
 * the configured instance through to the proxy and dispatcher.
 */
public class AuthorizationRegistry {

    private static final Logger log = Logger.getLogger(AuthorizationRegistry.class.getName());

    private static final AuthorizationRegistry INSTANCE = new AuthorizationRegistry();

    /** One factory per authorization id. */
    private final Map<String, ItaraAuthorizationFactory> factories = new ConcurrentHashMap<>();

    /** Active instances: id → (grouping key → instance). */
    private final Map<String, Map<ItaraAuthorizationGroupingKey, ItaraAuthorization>> instances = new ConcurrentHashMap<>();

    private AuthorizationRegistry() {}

    public static AuthorizationRegistry instance() {
        return INSTANCE;
    }

    public void registerFactory(ItaraAuthorizationFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered authorization factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

    public ItaraAuthorizationConfig parseConfig(String id, AuthorizationConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraAuthorizationFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No authorization factory registered for id '" + id + "'. "
                            + "Add the appropriate authorization jar to the classpath. "
                            + "Available ids: " + factories.keySet());
        }
        return factory.parseConfig(config);
    }

    public ItaraAuthorization getOrCreate(String id, ItaraAuthorizationConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraAuthorizationFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No authorization factory registered for id '" + id + "'. "
                            + "Available ids: " + factories.keySet());
        }

        Map<ItaraAuthorizationGroupingKey, ItaraAuthorization> byKey =
                instances.computeIfAbsent(normalizedId, k -> new ConcurrentHashMap<>());

        ItaraAuthorizationGroupingKey groupingKey = config.groupingKey();

        try {
            return byKey.computeIfAbsent(groupingKey, k -> {
                try {
                    ItaraAuthorization authorization = factory.create(config);
                    log.fine("[Itara] created authorization instance id=" + id
                            + " key=" + groupingKey
                            + " class=" + authorization.getClass().getName());
                    return authorization;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw new Exception(
                    "[Itara] Failed to create authorization instance id='" + id
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
