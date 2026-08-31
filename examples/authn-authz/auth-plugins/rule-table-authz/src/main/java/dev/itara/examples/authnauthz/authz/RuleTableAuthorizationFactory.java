package dev.itara.examples.authnauthz.authz;

import dev.itara.spi.authorization.AuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorizationFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RuleTableAuthorizationFactory implements ItaraAuthorizationFactory {

    @Override
    public String id() {
        return "rule-table";
    }

    @Override
    public ItaraAuthorizationConfig parseConfig(AuthorizationConfig config) {
        Set<String> allow = parseList(config.getParams().get("allow"));
        Set<String> deny = parseList(config.getParams().get("deny"));
        return new RuleTableAuthorizationConfig(allow, deny);
    }

    @Override
    public ItaraAuthorization create(ItaraAuthorizationConfig config) {
        return new RuleTableAuthorization();
    }

    private static Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
