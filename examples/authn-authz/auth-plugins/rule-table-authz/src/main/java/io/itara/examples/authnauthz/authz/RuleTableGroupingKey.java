package io.itara.examples.authnauthz.authz;

import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;

import java.util.Objects;
import java.util.Set;

class RuleTableGroupingKey implements ItaraAuthorizationGroupingKey {

    private final Set<String> allow;
    private final Set<String> deny;

    RuleTableGroupingKey(Set<String> allow, Set<String> deny) {
        this.allow = allow;
        this.deny = deny;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RuleTableGroupingKey)) return false;
        RuleTableGroupingKey o = (RuleTableGroupingKey) other;
        return Objects.equals(allow, o.allow) && Objects.equals(deny, o.deny);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allow, deny);
    }
}
