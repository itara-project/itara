package io.itara.examples.authnauthz.authz;

import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;

import java.util.Set;

/** Raw connection params: allow (comma-separated, optional), deny (comma-separated, optional). */
class RuleTableAuthorizationConfig implements ItaraAuthorizationConfig {

    private final Set<String> allow;
    private final Set<String> deny;
    private final RuleTableGroupingKey groupingKey;

    RuleTableAuthorizationConfig(Set<String> allow, Set<String> deny) {
        this.allow = allow;
        this.deny = deny;
        this.groupingKey = new RuleTableGroupingKey(allow, deny);
    }

    Set<String> getAllow() { return allow; }
    Set<String> getDeny()  { return deny; }

    @Override
    public ItaraAuthorizationGroupingKey groupingKey() {
        return groupingKey;
    }
}
