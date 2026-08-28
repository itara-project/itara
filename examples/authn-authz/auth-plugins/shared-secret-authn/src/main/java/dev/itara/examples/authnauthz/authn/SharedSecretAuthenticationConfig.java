package dev.itara.examples.authnauthz.authn;

import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthenticationGroupingKey;

/** Raw connection params: secret (required), subject (required). */
class SharedSecretAuthenticationConfig implements ItaraAuthenticationConfig {

    private final String secret;
    private final String subject;
    private final SharedSecretGroupingKey groupingKey;

    SharedSecretAuthenticationConfig(String secret, String subject) {
        this.secret = secret;
        this.subject = subject;
        this.groupingKey = new SharedSecretGroupingKey(secret, subject);
    }

    String getSecret()  { return secret; }
    String getSubject() { return subject; }

    @Override
    public ItaraAuthenticationGroupingKey groupingKey() {
        return groupingKey;
    }
}
