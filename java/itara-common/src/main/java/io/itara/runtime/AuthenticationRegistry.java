package io.itara.runtime;

import io.itara.spi.authentication.AuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthenticationFactory;
import io.itara.spi.authentication.ItaraAuthenticationGroupingKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available authentication implementations.
 *
 * Populated by the agent at startup via AuthenticationLoader, which
 * registers the built-in noop factory directly, then scans the classpath
 * for META-INF/itara/authentication descriptor files.
 *
 * The connection-processing loop looks up authentication by id to wire
 * the configured instance through to the proxy and dispatcher.
 */
public class AuthenticationRegistry {

    private static final Logger log = Logger.getLogger(AuthenticationRegistry.class.getName());

    private static final AuthenticationRegistry INSTANCE = new AuthenticationRegistry();

    /** One factory per authentication id. */
    private final Map<String, ItaraAuthenticationFactory> factories = new ConcurrentHashMap<>();

    /** Active instances: id → (grouping key → instance). */
    private final Map<String, Map<ItaraAuthenticationGroupingKey, ItaraAuthentication>> instances = new ConcurrentHashMap<>();

    private AuthenticationRegistry() {}

    public static AuthenticationRegistry instance() {
        return INSTANCE;
    }

    public void registerFactory(ItaraAuthenticationFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered authentication factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

    public ItaraAuthenticationConfig parseConfig(String id, AuthenticationConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraAuthenticationFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No authentication factory registered for id '" + id + "'. "
                            + "Add the appropriate authentication jar to the classpath. "
                            + "Available ids: " + factories.keySet());
        }
        return factory.parseConfig(config);
    }

    public ItaraAuthentication getOrCreate(String id, ItaraAuthenticationConfig config) throws Exception {
        String normalizedId = id.toLowerCase();
        ItaraAuthenticationFactory factory = factories.get(normalizedId);
        if (factory == null) {
            throw new IllegalStateException(
                    "[Itara] No authentication factory registered for id '" + id + "'. "
                            + "Available ids: " + factories.keySet());
        }

        Map<ItaraAuthenticationGroupingKey, ItaraAuthentication> byKey =
                instances.computeIfAbsent(normalizedId, k -> new ConcurrentHashMap<>());

        ItaraAuthenticationGroupingKey groupingKey = config.groupingKey();

        try {
            return byKey.computeIfAbsent(groupingKey, k -> {
                try {
                    ItaraAuthentication authentication = factory.create(config);
                    log.fine("[Itara] created authentication instance id=" + id
                            + " key=" + groupingKey
                            + " class=" + authentication.getClass().getName());
                    return authentication;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw new Exception(
                    "[Itara] Failed to create authentication instance id='" + id
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
