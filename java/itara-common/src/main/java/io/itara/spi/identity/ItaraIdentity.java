package io.itara.spi.identity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared identity type produced by authentication and consumed by
 * authorization (ADR 0024). Neither SPI needs to know about the other's
 * implementation — this type is the entire contract between them.
 *
 * Carries the minimum shape spec §15.6 requires:
 *   - subject identification (subject, displayName)
 *   - issuer and trust metadata (issuer, trustMechanism)
 *   - security scope and claims — an open, extensible set (claims)
 *
 * The claims map is also where implementation-specific fields beyond the
 * minimum live (ADR 0024's extensibility requirement) — put whatever a
 * given mechanism produces in there under whatever keys make sense; a
 * generic Map<String,Object> supports typed access for a consumer that
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

    public String getSubject()         { return subject; }
    public String getDisplayName()     { return displayName; }
    public String getIssuer()          { return issuer; }
    public String getTrustMechanism()  { return trustMechanism; }
    public Map<String, Object> getClaims() { return claims; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String subject;
        private String displayName;
        private String issuer;
        private String trustMechanism;
        private Map<String, Object> claims = Collections.emptyMap();

        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder issuer(String issuer) { this.issuer = issuer; return this; }
        public Builder trustMechanism(String trustMechanism) { this.trustMechanism = trustMechanism; return this; }
        public Builder claims(Map<String, Object> claims) {
            this.claims = (claims != null) ? claims : Collections.emptyMap();
            return this;
        }

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
