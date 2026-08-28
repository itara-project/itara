package dev.itara.spi.failuresemantics;

/**
 * Factory for {@link ItaraFailureSemantics} instances.
 *
 * <p>The factory — not {@link ItaraFailureSemantics} itself — is what the
 * agent discovers at startup, via META-INF/itara/failure-semantics on the
 * classpath, and calls {@link #create} once per connection that
 * references this factory's type identifier.
 *
 * <p>Two type identifiers are reserved by the spec (§14.2):
 * <ul>
 * <li>{@code noop} — no failure handling; errors surface immediately
 * (default)</li>
 * <li>{@code built-in} — standard retry/timeout/circuit-breaker
 * implementation</li>
 * </ul>
 */
public interface ItaraFailureSemanticsFactory {

    /**
     * The type identifier this factory handles.
     * Must match the 'id' field in the connection's failureSemantics block
     * in the wiring config. Case-insensitive.
     */
    String type();

    /**
     * Create a configured {@link ItaraFailureSemantics} instance for a
     * single connection.
     *
     * <p>Called once per connection at agent startup. Validate all required
     * parameters here — throw if anything is missing or invalid so that
     * the agent fails fast with a clear error rather than failing silently
     * at call time.
     *
     * @param config  The failure semantics configuration declared for this
     *                connection in the wiring config.
     * @return        A fully configured strategy instance for this connection.
     * @throws Exception if any required parameter is missing or invalid.
     */
    ItaraFailureSemantics create(FailureSemanticsConfig config) throws Exception;
}
