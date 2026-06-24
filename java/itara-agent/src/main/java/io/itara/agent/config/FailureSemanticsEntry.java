package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.itara.spi.failuresemantics.FailureSemanticsConfig;
import io.itara.util.DurationParser;

import java.util.Collections;
import java.util.Map;

/**
 * The failureSemantics block of a connection entry in the wiring config.
 *
 * Example YAML:
 *
 *   failureSemantics:
 *     id: built-in
 *     timeout: 2s
 *     handleTimeout: true
 *     absoluteTimeout: 10s
 *     maxRetry: 3
 *     params:
 *       waitDuration: 500ms
 *       slidingWindowSize: 10
 *
 * Absent means the noop implementation is used (§14.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureSemanticsEntry {

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTimeout() { return timeout; }
    public void setTimeout(String timeout) { this.timeout = timeout; }

    public boolean isHandleTimeout() { return handleTimeout; }
    public void setHandleTimeout(boolean handleTimeout) { this.handleTimeout = handleTimeout; }

    public String getAbsoluteTimeout() { return absoluteTimeout; }
    public void setAbsoluteTimeout(String absoluteTimeout) { this.absoluteTimeout = absoluteTimeout; }

    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * Translates this wiring config entry into the SPI's FailureSemanticsConfig.
     * maxRetry from the wiring config becomes maxAttempts = maxRetry + 1 here.
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
