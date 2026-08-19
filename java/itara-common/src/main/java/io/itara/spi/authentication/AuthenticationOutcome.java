package io.itara.spi.authentication;

import io.itara.spi.identity.ItaraIdentity;

import java.util.Optional;

/**
 * The result of a callee-side authentication attempt (spec §15.6).
 *
 * A rejection is an ordinary, expected outcome — not an exception. An
 * implementation that hits a genuine unexpected failure (can't reach a
 * JWKS endpoint, say) throws a normal Exception instead; the dispatcher
 * treats that the same way it treats any other unexpected failure.
 */
public final class AuthenticationOutcome {

    private final boolean accepted;
    private final ItaraIdentity identity;
    private final String rejectionReason;

    private AuthenticationOutcome(boolean accepted, ItaraIdentity identity, String rejectionReason) {
        this.accepted        = accepted;
        this.identity        = identity;
        this.rejectionReason = rejectionReason;
    }

    /** Accepted with no identity produced — e.g. the noop implementation (§15.1). */
    public static AuthenticationOutcome accepted() {
        return new AuthenticationOutcome(true, null, null);
    }

    /** Accepted, with the verified identity. */
    public static AuthenticationOutcome accepted(ItaraIdentity identity) {
        if (identity == null) {
            throw new NullPointerException("[Itara] identity must not be null — use accepted() for no identity");
        }
        return new AuthenticationOutcome(true, identity, null);
    }

    /** Rejected. Becomes the message of the PERMISSION error surfaced to the caller (ADR 0026). */
    public static AuthenticationOutcome rejected(String reason) {
        if (reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("[Itara] rejection reason must be a non-empty message");
        }
        return new AuthenticationOutcome(false, null, reason);
    }

    public boolean isAccepted() { return accepted; }
    public Optional<ItaraIdentity> getIdentity() { return Optional.ofNullable(identity); }
    public String getRejectionReason() { return rejectionReason; }

    @Override
    public String toString() {
        return accepted
                ? "AuthenticationOutcome{accepted, identity=" + identity + "}"
                : "AuthenticationOutcome{rejected, reason='" + rejectionReason + "'}";
    }
}
