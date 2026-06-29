package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [failure-semantics] section of a failure-semantics `.itara`
 * metadata file.
 *
 * Only present when artifact.kind = "failure-semantics". Ignored for
 * all other artifact kinds.
 *
 * Example TOML:
 *
 *   [failure-semantics]
 *
 *   [failure-semantics.capabilities]
 *   supports-external-timeout = true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureSemanticsMeta {

    private FailureSemanticsCapabilities capabilities = FailureSemanticsCapabilities.defaults();

    public FailureSemanticsCapabilities getCapabilities() { return capabilities; }
    public void setCapabilities(FailureSemanticsCapabilities c) {
        this.capabilities = c != null ? c : FailureSemanticsCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "FailureSemanticsMeta{capabilities=" + capabilities + "}";
    }
}
