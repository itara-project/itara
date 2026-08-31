package dev.itara.examples.authnauthz.authn;

import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthenticationFactory;

public class SharedSecretAuthenticationFactory implements ItaraAuthenticationFactory {

    @Override
    public String id() {
        return "shared-secret";
    }

    @Override
    public ItaraAuthenticationConfig parseConfig(AuthenticationConfig config) {
        String secret = requireParam(config, "secret");
        String subject = requireParam(config, "subject");
        return new SharedSecretAuthenticationConfig(secret, subject);
    }

    @Override
    public ItaraAuthentication create(ItaraAuthenticationConfig config) {
        return new SharedSecretAuthentication();
    }

    private static String requireParam(AuthenticationConfig config, String key) {
        String value = config.getParams().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[shared-secret example] authentication.params." + key
                            + " is required and must not be blank");
        }
        return value;
    }
}
