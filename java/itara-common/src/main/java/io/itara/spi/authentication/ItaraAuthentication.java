package io.itara.spi.authentication;

import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.identity.ItaraTransportCredential;

import java.util.Map;

/**
 * Service Provider Interface for Itara authentication implementations
 * (spec §15). Verifies a caller's claimed identity at the topology layer.
 * Component code is unaware of it.
 *
 * One instance is created per connection (or shared across connections
 * with an equal grouping key) at startup by the connection's configured
 * {@link ItaraAuthenticationFactory}. Because an instance may be shared,
 * the relevant connection's parsed config is passed on every call rather
 * than assumed from construction time — a shared instance can still
 * behave differently per connection.
 */
public interface ItaraAuthentication {

    /**
     * Caller side (§15.5). Produce whatever needs to travel with the
     * call to prove identity, as header entries the agent merges into
     * the outbound headers alongside everything else riding there.
     *
     * An empty map means nothing to add on this side — e.g. the
     * transport already conveys identity itself (an mTLS peer
     * certificate presented at handshake), so there is no assertion to
     * produce. Keys, values, and how many entries: entirely this
     * implementation's own encoding, opaque to the agent.
     *
     * Called once per logical call, before the failure semantics
     * implementation is invoked, and reused across any retries of that
     * call — not regenerated per attempt (ADR 0024).
     *
     * @param config The calling connection's parsed configuration.
     * @param target The operation being called — e.g. for an
     *               implementation that needs to set an audience claim
     *               on an outbound token.
     */
    Map<String, String> produceAssertion(ItaraAuthenticationConfig config, ItaraCallTarget target) throws Exception;

    /**
     * Callee side (§15.6). Verify whatever identity signal is available
     * and decide.
     *
     * @param config             The receiving connection's parsed configuration.
     * @param headers            Inbound headers — includes whatever this
     *                           implementation's caller-side counterpart
     *                           put there via produceAssertion(), plus
     *                           everything else riding along. This
     *                           implementation knows its own encoding.
     * @param transportCredential A connection-level credential the
     *                           transport itself terminated and
     *                           surfaced (e.g. a TLS peer certificate),
     *                           or null if the transport has nothing to
     *                           surface.
     */
    AuthenticationOutcome authenticate(ItaraAuthenticationConfig config, Map<String, String> headers,
                                       ItaraTransportCredential transportCredential) throws Exception;
}
