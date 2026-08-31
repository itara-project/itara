package dev.itara.spi.authorization;

import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;

/**
 * Service Provider Interface for Itara authorization implementations
 * (spec §16). Decides whether an authenticated caller is permitted to
 * invoke a specific operation. Component code is unaware of it.
 *
 * <p>This concerns node-to-node permission, not end-user permission — which
 * node may call what, not what a human end user is allowed to do (§16.1).
 * In the absence of authorization configuration on a connection, every
 * call is permitted — this is the default behavior.
 *
 * <p>One instance is created per connection (or shared across connections
 * with an equal grouping key) at startup. Because an instance may be
 * shared, the relevant connection's parsed config is passed on every
 * call rather than assumed from construction time.
 *
 * <p>This operates only on identity and the operation identifier — never
 * on deserialized business payloads, since {@link #authorize}'s signature
 * doesn't receive any. It MAY be evaluated before argument deserialization
 * occurs (§16.5).
 */
public interface ItaraAuthorization {

    /**
     * Decide whether the identified caller may invoke the identified
     * operation.
     *
     * <p>Deny by returning {@link AuthorizationDecision#deny}. A denial is
     * an ordinary, expected outcome, not an exception.
     *
     * @param config   The connection's parsed configuration.
     * @param identity The identity authentication produced for this
     *                 call, or empty when no authentication is
     *                 configured (§16.5) — an absent identity is a
     *                 legitimate input, not an error.
     * @param target   The operation being invoked, transport-agnostic
     *                 (§16.5).
     * @param headers  The raw inbound headers, for an implementation
     *                 that wants a signal beyond identity and target.
     * @return the permit or deny decision
     * @throws Exception if evaluating the decision fails unexpectedly
     *                   (distinct from an ordinary denial, which is not
     *                   an exception)
     */
    AuthorizationDecision authorize(ItaraAuthorizationConfig config,
                                    Optional<ItaraIdentity> identity,
                                    ItaraCallTarget target,
                                    Map<String, String> headers) throws Exception;
}
