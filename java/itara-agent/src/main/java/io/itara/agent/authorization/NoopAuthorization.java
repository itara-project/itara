package io.itara.agent.authorization;

import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.authorization.AuthorizationConfig;
import io.itara.spi.authorization.AuthorizationDecision;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorizationFactory;
import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;
import io.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;

/**
 * No-op authorization implementation. Every call is permitted — the
 * default when no authorization block is declared on a connection (§16.1).
 *
 * Registered directly by the agent at startup — not discovered via
 * META-INF/itara/authorization.
 */
public class NoopAuthorization implements ItaraAuthorization {

    @Override
    public AuthorizationDecision authorize(ItaraAuthorizationConfig config, Optional<ItaraIdentity> identity,
                                           ItaraCallTarget target, Map<String, String> headers) {
        return AuthorizationDecision.permit();
    }

    public static final class Factory implements ItaraAuthorizationFactory {

        @Override
        public String id() {
            return "noop";
        }

        @Override
        public ItaraAuthorizationConfig parseConfig(AuthorizationConfig config) {
            return NoopConfig.INSTANCE;
        }

        @Override
        public ItaraAuthorization create(ItaraAuthorizationConfig config) {
            return new NoopAuthorization();
        }

        private enum NoopConfig implements ItaraAuthorizationConfig, ItaraAuthorizationGroupingKey {
            INSTANCE;

            @Override
            public ItaraAuthorizationGroupingKey groupingKey() {
                return this;
            }
        }
    }
}
