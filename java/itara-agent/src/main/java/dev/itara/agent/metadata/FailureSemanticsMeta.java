package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [failure-semantics] section of a failure-semantics `.itara`
 * metadata file.
 *
 * <p>Only present when artifact.kind = "failure-semantics". Ignored for
 * all other artifact kinds.
 *
 * <p>Example TOML:
 * <pre>{@code
 *   [failure-semantics]
 *
 *   [failure-semantics.capabilities]
 *   supports-external-timeout = true
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureSemanticsMeta {

    private FailureSemanticsCapabilities capabilities = FailureSemanticsCapabilities.defaults();

    /** @return this implementation's declared capabilities */
    public FailureSemanticsCapabilities getCapabilities() { return capabilities; }
    /** @param c this implementation's declared capabilities; null falls back to {@link FailureSemanticsCapabilities#defaults()} */
    public void setCapabilities(FailureSemanticsCapabilities c) {
        this.capabilities = c != null ? c : FailureSemanticsCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "FailureSemanticsMeta{capabilities=" + capabilities + "}";
    }
}
