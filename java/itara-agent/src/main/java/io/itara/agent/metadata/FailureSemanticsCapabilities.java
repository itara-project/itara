package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [failure-semantics.capabilities] section of a failure-semantics
 * `.itara` metadata file.
 *
 * Declares what the failure semantics implementation is capable of,
 * so the agent and tooling can validate timeout configuration before
 * loading anything.
 *
 * Defaults to false when the section is absent — a failure semantics
 * implementation that does not declare this capability is assumed not
 * to support it (§14.10).
 *
 * Example TOML:
 *
 *   [failure-semantics.capabilities]
 *   supports-external-timeout = true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureSemanticsCapabilities {

    /**
     * Whether this implementation can enforce the per-attempt timeout
     * by external interruption of the transport thread (§14.10).
     * Defaults to false — implementations must opt in explicitly.
     */
    @JsonProperty("supports-external-timeout")
    private boolean supportsExternalTimeout = false;

    public boolean isSupportsExternalTimeout()        { return supportsExternalTimeout; }
    public void setSupportsExternalTimeout(boolean v) { this.supportsExternalTimeout = v; }

    public static FailureSemanticsCapabilities defaults() {
        return new FailureSemanticsCapabilities();
    }

    @Override
    public String toString() {
        return "FailureSemanticsCapabilities{supportsExternalTimeout=" + supportsExternalTimeout + "}";
    }
}
