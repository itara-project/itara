package dev.itara.runtime;

import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthenticationFactory;
import dev.itara.spi.authentication.ItaraAuthenticationGroupingKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available authentication implementations.
 *
 * <p>Populated by the agent at startup via AuthenticationLoader, which
 * registers the built-in noop factory directly, then scans the classpath
 * for META-INF/itara/authentication descriptor files.
 *
 * <p>The connection-processing loop looks up authentication by id to wire
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

    /** @return the singleton registry instance */
    public static AuthenticationRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register an authentication factory.
     * Called by AuthenticationLoader during agent startup before any
     * connections are processed.
     */
    public void registerFactory(ItaraAuthenticationFactory factory) {
        factories.put(factory.id().toLowerCase(), factory);
        log.fine("[Itara] registered authentication factory id=" + factory.id()
                + " class=" + factory.getClass().getName());
    }

    /**
     * Parses the raw authentication config into a typed, implementation-
     * specific config via the registered factory. The returned object
     * carries the grouping key and is passed directly to getOrCreate() —
     * no second parse.
     *
     * @param id      The authentication id from the wiring config authentication block
     * @param config  The raw authentication config for this connection
     * @return        The parsed, typed authentication config
     * @throws Exception if no factory is registered for the id, or if parsing fails
     */
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

    /**
     * Returns an existing authentication instance for the given parsed
     * config's grouping key, or creates a new one via the factory if
     * none exists.
     *
     * @param id      The authentication id from the wiring config authentication block
     * @param config  The parsed authentication config, as returned by parseConfig()
     * @return        The authentication instance responsible for this connection
     * @throws Exception if the factory fails to create a new instance
     */
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
