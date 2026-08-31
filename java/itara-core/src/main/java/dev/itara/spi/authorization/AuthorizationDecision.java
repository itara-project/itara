package dev.itara.spi.authorization;

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

    /** The call is permitted.
     *
     * @return An object expressing permission
     */
    public static AuthorizationDecision permit() {
        return new AuthorizationDecision(true, null);
    }

    /** Denied. Becomes the message of the PERMISSION error surfaced to the caller (ADR 0026).
     *
     * @param reason the reason for the rejection
     * @return An object expressing denial of permission
     */
    public static AuthorizationDecision deny(String reason) {
        if (reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("[Itara] denial reason must be a non-empty message");
        }
        return new AuthorizationDecision(false, reason);
    }

    /**
     * Returns whether the call is permitted.
     *
     * @return whether the call is permitted
     */
    public boolean isPermitted() { return permitted; }
    /**
     * Returns the denial reason, or null if permitted.
     *
     * @return the denial reason, or null if permitted
     */
    public String getDenialReason() { return denialReason; }

    @Override
    public String toString() {
        return permitted
                ? "AuthorizationDecision{permitted}"
                : "AuthorizationDecision{denied, reason='" + denialReason + "'}";
    }
}
