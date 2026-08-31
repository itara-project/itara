package dev.itara.spi.failuresemantics;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's failure semantics strategy.
 *
 * <p>Constructed by the agent at startup from the wiring configuration and
 * passed to {@link ItaraFailureSemanticsFactory#create}. The agent owns
 * the translation from the wiring config format — implementations receive
 * this type only and are decoupled from the wiring config structure.
 *
 * <p>All fields are optional. Implementations should apply sensible defaults
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

    /**
     * Returns maximum number of attempts including the first, or null for the implementation's own default.
     *
     * @return maximum number of attempts including the first, or null for the implementation's own default
     */
    public Integer getMaxAttempts()     { return maxAttempts; }
    /**
     * Returns the per-attempt timeout, or null if none is configured.
     *
     * @return the per-attempt timeout, or null if none is configured
     */
    public Duration getTimeout()        { return timeout; }
    /**
     * Returns whether this implementation should enforce the per-attempt timeout itself.
     *
     * @return whether this implementation should enforce the per-attempt timeout itself
     */
    public boolean isHandleTimeout()    { return handleTimeout; }
    /**
     * Returns the hard ceiling on total execution time across all attempts, or null if none is configured.
     *
     * @return the hard ceiling on total execution time across all attempts, or null if none is configured
     */
    public Duration getAbsoluteTimeout(){ return absoluteTimeout; }
    /**
     * Returns implementation-specific connection parameters; never null.
     *
     * @return implementation-specific connection parameters; never null
     */
    public Map<String, String> getParams() { return params; }

    /**
     * Returns a new builder for a {@link FailureSemanticsConfig}.
     *
     * @return a new builder for a {@link FailureSemanticsConfig}
     */
    public static Builder builder() { return new Builder(); }

    /** Builder for {@link FailureSemanticsConfig}. */
    public static final class Builder {
        private Integer maxAttempts;
        private Duration timeout;
        private boolean handleTimeout;
        private Duration absoluteTimeout;
        private Map<String, String> params = Collections.emptyMap();

        /** Constructs a new, empty builder. */
        public Builder() {}

        /**
         * Sets the maximum number of attempts, including the first.
         *
         * @param maxAttempts maximum number of attempts including the first, or null for the implementation's own default
         * @return this builder
         */
        public Builder maxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        /**
         * Sets the per-attempt timeout.
         *
         * @param timeout the per-attempt timeout, or null for none
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        /**
         * Sets whether this implementation should enforce the per-attempt timeout itself.
         *
         * @param handleTimeout whether this implementation should enforce the per-attempt timeout itself
         * @return this builder
         */
        public Builder handleTimeout(boolean handleTimeout) {
            this.handleTimeout = handleTimeout;
            return this;
        }
        /**
         * Sets the hard ceiling on total execution time across all attempts.
         *
         * @param absoluteTimeout the hard ceiling on total execution time, or null for none
         * @return this builder
         */
        public Builder absoluteTimeout(Duration absoluteTimeout) {
            this.absoluteTimeout = absoluteTimeout;
            return this;
        }
        /**
         * Sets the implementation-specific connection parameters.
         *
         * @param params implementation-specific connection parameters; null is treated as empty
         * @return this builder
         */
        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        /**
         * Returns the built {@link FailureSemanticsConfig}.
         *
         * @return the built {@link FailureSemanticsConfig}
         */
        public FailureSemanticsConfig build() {
            return new FailureSemanticsConfig(this);
        }
    }
}
