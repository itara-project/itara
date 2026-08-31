package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import dev.itara.spi.failuresemantics.FailureSemanticsConfig;
import dev.itara.util.DurationParser;

import java.util.Collections;
import java.util.Map;

/**
 * The failureSemantics block of a connection entry in the wiring config.
 *
 * <p>Example YAML:
 * <pre>{@code
 * failureSemantics:
 *   id: built-in
 *   timeout: 2s
 *   handleTimeout: true
 *   absoluteTimeout: 10s
 *   maxRetry: 3
 *   params:
 *     waitDuration: 500ms
 *     slidingWindowSize: 10
 * }</pre>
 *
 * <p>Absent means the noop implementation is used (§14.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureSemanticsEntry {

    /** Required for deserialization. */
    public FailureSemanticsEntry() {}

    @JsonSetter(nulls = Nulls.SKIP)
    private String id = "noop";

    /** Per-attempt timeout as an ISO-8601 duration string, e.g. "2s", "500ms". */
    private String timeout;

    /** Whether the implementation should enforce the timeout externally (§14.10). */
    private boolean handleTimeout = false;

    /** Hard ceiling on total execution time across all attempts, e.g. "10s". */
    private String absoluteTimeout;

    /** Maximum number of retries. Attempts = maxRetry + 1. */
    private Integer maxRetry;

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    /**
     * Returns the failure semantics type id.
     *
     * @return the failure semantics type id
     */
    public String getId() { return id; }
    /**
     * Sets the failure semantics type id.
     *
     * @param id the failure semantics type id
     */
    public void setId(String id) { this.id = id; }

    /**
     * Returns the per-attempt timeout as an ISO-8601 duration string, or null if not set.
     *
     * @return the per-attempt timeout as an ISO-8601 duration string, or null if not set
     */
    public String getTimeout() { return timeout; }
    /**
     * Sets the per-attempt timeout.
     *
     * @param timeout the per-attempt timeout as an ISO-8601 duration string
     */
    public void setTimeout(String timeout) { this.timeout = timeout; }

    /**
     * Returns whether the implementation should enforce the timeout externally.
     *
     * @return whether the implementation should enforce the timeout externally
     */
    public boolean isHandleTimeout() { return handleTimeout; }
    /**
     * Sets whether the implementation should enforce the timeout externally.
     *
     * @param handleTimeout whether the implementation should enforce the timeout externally
     */
    public void setHandleTimeout(boolean handleTimeout) { this.handleTimeout = handleTimeout; }

    /**
     * Returns the hard ceiling on total execution time, or null if not set.
     *
     * @return the hard ceiling on total execution time, or null if not set
     */
    public String getAbsoluteTimeout() { return absoluteTimeout; }
    /**
     * Sets the hard ceiling on total execution time.
     *
     * @param absoluteTimeout the hard ceiling on total execution time
     */
    public void setAbsoluteTimeout(String absoluteTimeout) { this.absoluteTimeout = absoluteTimeout; }

    /**
     * Returns the maximum number of retries, or null if not set.
     *
     * @return the maximum number of retries, or null if not set
     */
    public Integer getMaxRetry() { return maxRetry; }
    /**
     * Sets the maximum number of retries.
     *
     * @param maxRetry the maximum number of retries
     */
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }

    /**
     * Returns implementation-specific connection parameters; never null.
     *
     * @return implementation-specific connection parameters; never null
     */
    public Map<String, String> getParams() { return params; }
    /**
     * Sets the implementation-specific connection parameters.
     *
     * @param params implementation-specific connection parameters; null is treated as empty
     */
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * Translates this wiring config entry into the SPI's FailureSemanticsConfig.
     * maxRetry from the wiring config becomes maxAttempts = maxRetry + 1 here.
     *
     * @return the translated SPI-facing config
     */
    public FailureSemanticsConfig toSpiConfig() {
        try {
            return FailureSemanticsConfig.builder()
                    .timeout(timeout != null ? DurationParser.parse(timeout) : null)
                    .handleTimeout(handleTimeout)
                    .absoluteTimeout(absoluteTimeout != null ? DurationParser.parse(absoluteTimeout) : null)
                    .maxAttempts(maxRetry != null ? maxRetry + 1 : null)
                    .params(params)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid duration in failureSemantics block: " + e.getMessage(), e);
        }
    }
}
