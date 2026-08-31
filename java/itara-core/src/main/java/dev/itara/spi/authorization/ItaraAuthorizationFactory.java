package dev.itara.spi.authorization;

/**
 * Factory for {@link ItaraAuthorization} instances.
 *
 * <p>The factory — not {@link ItaraAuthorization} itself — is what the
 * agent discovers at startup, via META-INF/itara/authorization on the
 * classpath (§16.3), and uses it to parse connection configs and create
 * authorization instances on demand.
 *
 * <p>The factory is called once per connection to parse its config. Whether
 * {@link #create} is called again depends on the grouping key returned by
 * the parsed {@link ItaraAuthorizationConfig}: connections that produce
 * equal keys share one instance.
 *
 * <p>One type identifier is reserved by the spec (§16.1, §16.2):
 * <ul>
 * <li>{@code noop} — no authorization; every call is permitted (default)</li>
 * </ul>
 * The spec does not reserve identifiers for specific mechanisms (RBAC,
 * ACL, policy engine integration, or otherwise) — implementations may
 * define any non-reserved identifier.
 */
public interface ItaraAuthorizationFactory {

    /**
     * The type identifier this factory handles. Must match the 'id'
     * field in the connection's authorization block. Case-insensitive.
     *
     * @return the type identifier this factory handles
     */
    String id();

    /**
     * Parse the raw authorization config into a typed, implementation-
     * specific config object.
     *
     * <p>Called once per connection at agent startup. Validate all required
     * parameters here — throw if anything is missing or invalid so that
     * the agent fails fast with a clear error rather than failing silently
     * at call time.
     *
     * @param config  The raw authorization configuration for this connection.
     * @return        A fully parsed, typed config for this connection.
     * @throws Exception if any required parameter is missing or invalid.
     */
    ItaraAuthorizationConfig parseConfig(AuthorizationConfig config) throws Exception;

    /**
     * Create a new authorization instance for the given config.
     *
     * <p>Called at most once per unique grouping key. The config received here
     * is the same object returned by {@link #parseConfig} — no second parse.
     *
     * @param config  The parsed config for this authorization instance.
     * @return        A new, ready-to-use authorization instance.
     * @throws Exception if the instance cannot be created.
     */
    ItaraAuthorization create(ItaraAuthorizationConfig config) throws Exception;
}
