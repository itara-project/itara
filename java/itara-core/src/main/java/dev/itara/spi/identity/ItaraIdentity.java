package dev.itara.spi.identity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared identity type produced by authentication and consumed by
 * authorization (ADR 0024). Neither SPI needs to know about the other's
 * implementation — this type is the entire contract between them.
 *
 * <p>Carries the minimum shape spec §15.6 requires:
 * <ul>
 * <li>subject identification (subject, displayName)</li>
 * <li>issuer and trust metadata (issuer, trustMechanism)</li>
 * <li>security scope and claims — an open, extensible set (claims)</li>
 * </ul>
 *
 * <p>The claims map is also where implementation-specific fields beyond the
 * minimum live (ADR 0024's extensibility requirement) — put whatever a
 * given mechanism produces in there under whatever keys make sense; a
 * generic {@code Map<String,Object>} supports typed access for a consumer that
 * knows to expect a specific key, without requiring every authorization
 * implementation to understand every authentication implementation's
 * concrete type.
 */
public final class ItaraIdentity {

    private final String subject;
    private final String displayName;
    private final String issuer;
    private final String trustMechanism;
    private final Map<String, Object> claims;

    private ItaraIdentity(Builder builder) {
        this.subject         = builder.subject;
        this.displayName     = builder.displayName;
        this.issuer          = builder.issuer;
        this.trustMechanism  = builder.trustMechanism;
        this.claims          = Collections.unmodifiableMap(new HashMap<>(builder.claims));
    }

    /**
     * Returns subject identification sufficient to identify the caller; never null.
     *
     * @return subject identification sufficient to identify the caller; never null
     */
    public String getSubject()         { return subject; }
    /**
     * Returns a short human-readable display name for the subject, or null if none was produced.
     *
     * @return a short human-readable display name for the subject, or null if none was produced
     */
    public String getDisplayName()     { return displayName; }
    /**
     * Returns what vouched for this identity, or null if not applicable to this mechanism.
     *
     * @return what vouched for this identity, or null if not applicable to this mechanism
     */
    public String getIssuer()          { return issuer; }
    /**
     * Returns by what mechanism this identity was verified, or null if not applicable.
     *
     * @return by what mechanism this identity was verified, or null if not applicable
     */
    public String getTrustMechanism()  { return trustMechanism; }
    /**
     * Returns the open, extensible set of claims this identity carries; never null.
     *
     * @return the open, extensible set of claims this identity carries; never null
     */
    public Map<String, Object> getClaims() { return claims; }

    /**
     * Returns a new builder for an {@link ItaraIdentity}.
     *
     * @return a new builder for an {@link ItaraIdentity}
     */
    public static Builder builder() { return new Builder(); }

    /** Builder for {@link ItaraIdentity}. */
    public static final class Builder {
        private String subject;
        private String displayName;
        private String issuer;
        private String trustMechanism;
        private Map<String, Object> claims = Collections.emptyMap();

        /** Constructs a new, empty builder. */
        public Builder() {}

        /**
         * Sets the subject identification.
         *
         * @param subject subject identification sufficient to identify the caller; required
         * @return this builder
         */
        public Builder subject(String subject) { this.subject = subject; return this; }
        /**
         * Sets the display name.
         *
         * @param displayName a short human-readable display name for the subject
         * @return this builder
         */
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        /**
         * Sets the issuer.
         *
         * @param issuer what vouched for this identity
         * @return this builder
         */
        public Builder issuer(String issuer) { this.issuer = issuer; return this; }
        /**
         * Sets the trust mechanism.
         *
         * @param trustMechanism by what mechanism this identity was verified
         * @return this builder
         */
        public Builder trustMechanism(String trustMechanism) { this.trustMechanism = trustMechanism; return this; }
        /**
         * Sets the claims.
         *
         * @param claims the open, extensible set of claims this identity carries; null is treated as empty
         * @return this builder
         */
        public Builder claims(Map<String, Object> claims) {
            this.claims = (claims != null) ? claims : Collections.emptyMap();
            return this;
        }

        /**
         * Builds the identity.
         *
         * @return the built {@link ItaraIdentity}
         * @throws IllegalStateException if subject is null or empty
         */
        public ItaraIdentity build() {
            if (subject == null || subject.isEmpty()) {
                throw new IllegalStateException("[Itara] ItaraIdentity requires a non-empty subject");
            }
            return new ItaraIdentity(this);
        }
    }

    @Override
    public String toString() {
        // Claim values are not printed — they may carry sensitive data. Keys only.
        return "ItaraIdentity{subject='" + subject + "', displayName='" + displayName
                + "', issuer='" + issuer + "', trustMechanism='" + trustMechanism
                + "', claimKeys=" + claims.keySet() + "}";
    }
}
