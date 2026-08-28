package dev.itara.examples.authnauthz.authn;

import dev.itara.spi.authentication.ItaraAuthenticationGroupingKey;

import java.util.Objects;

/** Two connections share one instance only if they'd behave identically. */
class SharedSecretGroupingKey implements ItaraAuthenticationGroupingKey {

    private final String secret;
    private final String subject;

    SharedSecretGroupingKey(String secret, String subject) {
        this.secret = secret;
        this.subject = subject;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SharedSecretGroupingKey)) return false;
        SharedSecretGroupingKey o = (SharedSecretGroupingKey) other;
        return Objects.equals(secret, o.secret) && Objects.equals(subject, o.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(secret, subject);
    }
}
