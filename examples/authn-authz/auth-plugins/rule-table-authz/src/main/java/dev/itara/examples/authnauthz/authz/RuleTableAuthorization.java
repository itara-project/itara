package dev.itara.examples.authnauthz.authz;

import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.authorization.AuthorizationDecision;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import dev.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;

/**
 * Illustrative per-method allow/deny authorization for this example only
 * — not part of Itara's core distribution. Reads two lists of method
 * names from its connection's own config: deny always wins; a non-empty
 * allow list then restricts everything else. A real authorization
 * implementation would typically decide against the identity
 * authentication produced too, not just the method name — this example
 * keeps the rule flat and method-only to keep the outcome legible from
 * the wiring config alone.
 */
public class RuleTableAuthorization implements ItaraAuthorization {

    @Override
    public AuthorizationDecision authorize(ItaraAuthorizationConfig config, Optional<ItaraIdentity> identity, ItaraCallTarget target, Map<String, String> headers) {
        RuleTableAuthorizationConfig cfg = (RuleTableAuthorizationConfig) config;
        String method = target.getMethod();

        if (cfg.getDeny().contains(method)) {
            return AuthorizationDecision.deny(
                    "method '" + method + "' is on this connection's deny list");
        }
        if (!cfg.getAllow().isEmpty() && !cfg.getAllow().contains(method)) {
            return AuthorizationDecision.deny(
                    "method '" + method + "' is not on this connection's allow list");
        }
        return AuthorizationDecision.permit();
    }
}
