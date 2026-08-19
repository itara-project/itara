package io.itara.spi.authorization;

/**
 * The result of an authorization decision (spec §16.5). Denial is an
 * ordinary, expected outcome, same treatment as AuthenticationOutcome's
 * rejection — not an exception.
 */
public final class AuthorizationDecision {

    private final boolean permitted;
    private final String denialReason;

    private AuthorizationDecision(boolean permitted, String denialReason) {
        this.permitted    = permitted;
        this.denialReason = denialReason;
    }

    public static AuthorizationDecision permit() {
        return new AuthorizationDecision(true, null);
    }

    /** Becomes the message of the PERMISSION error surfaced to the caller (ADR 0026). */
    public static AuthorizationDecision deny(String reason) {
        if (reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("[Itara] denial reason must be a non-empty message");
        }
        return new AuthorizationDecision(false, reason);
    }

    public boolean isPermitted() { return permitted; }
    public String getDenialReason() { return denialReason; }

    @Override
    public String toString() {
        return permitted
                ? "AuthorizationDecision{permitted}"
                : "AuthorizationDecision{denied, reason='" + denialReason + "'}";
    }
}
