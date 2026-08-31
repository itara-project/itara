package dev.itara.agent.authorization;

import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.authorization.AuthorizationConfig;
import dev.itara.spi.authorization.AuthorizationDecision;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorizationFactory;
import dev.itara.spi.authorization.ItaraAuthorizationGroupingKey;
import dev.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;

/**
 * No-op authorization implementation. Every call is permitted — the
 * default when no authorization block is declared on a connection (§16.1).
 *
 * <p>Registered directly by the agent at startup — not discovered via
 * META-INF/itara/authorization.
 */
public class NoopAuthorization implements ItaraAuthorization {

    /** Constructs the no-op authorization implementation. */
    public NoopAuthorization() {}

    @Override
    public AuthorizationDecision authorize(ItaraAuthorizationConfig config, Optional<ItaraIdentity> identity,
                                           ItaraCallTarget target, Map<String, String> headers) {
        return AuthorizationDecision.permit();
    }


    /**
     * Config is accepted but ignored — the noop strategy has no
     * configurable behaviour. Stateless, so every connection using it
     * shares one grouping key (and therefore one instance).
     */
    public static final class Factory implements ItaraAuthorizationFactory {

        /** Constructs the factory for the no-op authorization implementation. */
        public Factory() {}

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
