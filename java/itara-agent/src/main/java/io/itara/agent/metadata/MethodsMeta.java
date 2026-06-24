package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The optional [methods] section of an API artifact's .itara metadata file.
 *
 * Declares which methods on this contract are not idempotent. The agent
 * reads this at startup and passes the set to ItaraProxyHandler so that
 * failure semantics implementations know which calls must not be retried
 * unless explicitly configured to do so (§14.5).
 *
 * Present only on kind = "api" artifacts. Absent means all methods are
 * treated as idempotent — the safe default for retry purposes (§5.4).
 *
 * Example:
 *   [methods]
 *   non-idempotent = ["divide", "transfer", "placeOrder"]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MethodsMeta {

    @JsonProperty("non-idempotent")
    private List<String> nonIdempotentMethods = new ArrayList<>();

    public List<String> getNonIdempotentMethods() { return nonIdempotentMethods; }
    public void setNonIdempotentMethods(List<String> nonIdempotentMethods) {
        this.nonIdempotentMethods = nonIdempotentMethods != null ? nonIdempotentMethods : new ArrayList<>();
    }

    /**
     * Returns the non-idempotent method names as an immutable set
     * for O(1) lookup in the proxy handler at call time.
     */
    public Set<String> nonIdempotentSet() {
        return Collections.unmodifiableSet(new HashSet<>(nonIdempotentMethods));
    }

    public static MethodsMeta ofEmpty() {
        return new MethodsMeta();
    }

    @Override
    public String toString() {
        return "MethodsMeta{nonIdempotent=" + nonIdempotentMethods + "}";
    }
}
