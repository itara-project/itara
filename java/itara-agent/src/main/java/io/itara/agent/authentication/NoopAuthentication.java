package io.itara.agent.authentication;

import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.authentication.AuthenticationConfig;
import io.itara.spi.authentication.AuthenticationOutcome;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthenticationFactory;
import io.itara.spi.authentication.ItaraAuthenticationGroupingKey;
import io.itara.spi.identity.ItaraTransportCredential;

import java.util.Collections;
import java.util.Map;

/**
 * No-op authentication implementation. Nothing is verified or asserted
 * on either side — the default when no authentication block is declared
 * on a connection (§15.1).
 *
 * Registered directly by the agent at startup — not discovered via
 * META-INF/itara/authentication.
 */
public class NoopAuthentication implements ItaraAuthentication {

    @Override
    public Map<String, String> produceAssertion(ItaraAuthenticationConfig config, ItaraCallTarget target) {
        return Collections.emptyMap();
    }

    @Override
    public AuthenticationOutcome authenticate(ItaraAuthenticationConfig config, Map<String, String> headers, ItaraTransportCredential transportCredential) {
        return AuthenticationOutcome.accepted();
    }

    /**
     * Config is accepted but ignored — the noop strategy has no
     * configurable behaviour. Stateless, so every connection using it
     * shares one grouping key (and therefore one instance).
     */
    public static final class Factory implements ItaraAuthenticationFactory {

        @Override
        public String id() {
            return "noop";
        }

        @Override
        public ItaraAuthenticationConfig parseConfig(AuthenticationConfig config) {
            return NoopConfig.INSTANCE;
        }

        @Override
        public ItaraAuthentication create(ItaraAuthenticationConfig config) {
            return new NoopAuthentication();
        }

        private enum NoopConfig implements ItaraAuthenticationConfig, ItaraAuthenticationGroupingKey {
            INSTANCE;

            @Override
            public ItaraAuthenticationGroupingKey groupingKey() {
                return this;
            }
        }
    }
}
