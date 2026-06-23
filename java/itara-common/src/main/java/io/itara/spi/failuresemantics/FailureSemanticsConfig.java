package io.itara.spi.failuresemantics;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's failure semantics strategy.
 *
 * Constructed by the agent at startup from the wiring configuration and
 * passed to {@link ItaraFailureSemanticsFactory#create}. The agent owns
 * the translation from the wiring config format — implementations receive
 * this type only and are decoupled from the wiring config structure.
 *
 * All fields are optional. Implementations should apply sensible defaults
 * for any field that is null.
 */
public final class FailureSemanticsConfig {

    /**
     * Maximum number of attempts, including the first.
     * Null means the implementation should apply its own default.
     */
    private final Integer maxAttempts;

    /**
     * Per-attempt timeout. Passed to the transport on every attempt via
     * {@link TransportCall#call(Duration)} regardless of whether the
     * transport or the implementation enforces it (§14.10).
     * Null means no per-attempt timeout is configured.
     */
    private final Duration timeout;

    /**
     * Whether this implementation should enforce the per-attempt timeout
     * by external interruption of the transport call (§14.10).
     */
    private final boolean handleTimeout;

    /**
     * Hard ceiling on total execution time across all attempts, retries,
     * and waits. Null means no absolute timeout is configured.
     */
    private final Duration absoluteTimeout;

    /**
     * Implementation-specific parameters from the wiring config params block.
     * The keys and values are implementation-defined. Never null — absent
     * params block yields an empty map.
     */
    private final Map<String, String> params;

    private FailureSemanticsConfig(Builder builder) {
        this.maxAttempts     = builder.maxAttempts;
        this.timeout         = builder.timeout;
        this.handleTimeout   = builder.handleTimeout;
        this.absoluteTimeout = builder.absoluteTimeout;
        this.params          = Collections.unmodifiableMap(builder.params);
    }

    public Integer getMaxAttempts()     { return maxAttempts; }
    public Duration getTimeout()        { return timeout; }
    public boolean isHandleTimeout()    { return handleTimeout; }
    public Duration getAbsoluteTimeout(){ return absoluteTimeout; }
    public Map<String, String> getParams() { return params; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer maxAttempts;
        private Duration timeout;
        private boolean handleTimeout;
        private Duration absoluteTimeout;
        private Map<String, String> params = Collections.emptyMap();

        public Builder maxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        public Builder handleTimeout(boolean handleTimeout) {
            this.handleTimeout = handleTimeout;
            return this;
        }
        public Builder absoluteTimeout(Duration absoluteTimeout) {
            this.absoluteTimeout = absoluteTimeout;
            return this;
        }
        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        public FailureSemanticsConfig build() {
            return new FailureSemanticsConfig(this);
        }
    }
}
