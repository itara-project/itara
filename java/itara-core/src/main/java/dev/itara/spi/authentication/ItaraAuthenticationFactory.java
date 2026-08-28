package dev.itara.spi.authentication;

/**
 * Factory for {@link ItaraAuthentication} instances.
 *
 * <p>The factory — not {@link ItaraAuthentication} itself — is what the
 * agent discovers at startup, via META-INF/itara/authentication on the
 * classpath (§15.3), and uses it to parse connection configs and create
 * authentication instances on demand.
 *
 * <p>The factory is called once per connection to parse its config. Whether
 * {@link #create} is called again depends on the grouping key returned by
 * the parsed {@link ItaraAuthenticationConfig}: connections that produce
 * equal keys share one instance — for example, one shared client for a
 * JWKS endpoint rather than one per connection (§15.3).
 *
 * <p>One type identifier is reserved by the spec (§15.1, §15.2):
 * <ul>
 * <li>{@code noop} — no authentication; no identity is verified or
 * asserted (default)</li>
 * </ul>
 * The spec does not reserve identifiers for specific mechanisms (mTLS,
 * shared secret, JWT, or otherwise) — implementations may define any
 * non-reserved identifier.
 */
public interface ItaraAuthenticationFactory {

    /**
     * The type identifier this factory handles. Must match the 'id'
     * field in the connection's authentication block. Case-insensitive.
     */
    String id();

    /**
     * Parse the raw authentication config into a typed, implementation-
     * specific config object.
     *
     * <p>Called once per connection at agent startup. Validate all required
     * parameters here — throw if anything is missing or invalid so that
     * the agent fails fast with a clear error rather than failing silently
     * at call time.
     *
     * @param config  The raw authentication configuration for this connection.
     * @return        A fully parsed, typed config for this connection.
     * @throws Exception if any required parameter is missing or invalid.
     */
    ItaraAuthenticationConfig parseConfig(AuthenticationConfig config) throws Exception;

    /**
     * Create a new authentication instance for the given config.
     *
     * <p>Called at most once per unique grouping key. The config received here
     * is the same object returned by {@link #parseConfig} — no second parse.
     *
     * @param config  The parsed config for this authentication instance.
     * @return        A new, ready-to-use authentication instance.
     * @throws Exception if the instance cannot be created.
     */
    ItaraAuthentication create(ItaraAuthenticationConfig config) throws Exception;
}
