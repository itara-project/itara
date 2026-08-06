package io.itara.spi.authorization;

import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;

/**
 * Service Provider Interface for Itara authorization implementations
 * (spec §16). Decides whether an authenticated caller is permitted to
 * invoke a specific operation. Component code is unaware of it.
 *
 * One instance is created per connection (or shared across connections
 * with an equal grouping key) at startup. Because an instance may be
 * shared, the relevant connection's parsed config is passed on every
 * call rather than assumed from construction time.
 */
public interface ItaraAuthorization {

    /**
     * @param config   The connection's parsed configuration.
     * @param identity The identity authentication produced for this
     *                 call, or empty when no authentication is
     *                 configured (§16.5) — an absent identity is a
     *                 legitimate input, not an error.
     * @param target   The operation being invoked, transport-agnostic
     *                 (§16.5).
     * @param headers  The raw inbound headers, for an implementation
     *                 that wants a signal beyond identity and target.
     */
    AuthorizationDecision authorize(ItaraAuthorizationConfig config,
                                    Optional<ItaraIdentity> identity,
                                    ItaraCallTarget target,
                                    Map<String, String> headers) throws Exception;
}
