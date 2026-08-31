package dev.itara.spi.authorization;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's authorization implementation.
 *
 * <p>Constructed by the agent at startup from the wiring configuration and
 * passed to {@link ItaraAuthorizationFactory#parseConfig}. The agent owns
 * the translation from the wiring config format — implementations receive
 * this type only and are decoupled from the wiring config structure.
 */
public final class AuthorizationConfig {

    /**
     * Implementation-specific parameters from the wiring config params block.
     * The keys and values are implementation-defined. Never null — an
     * absent params block yields an empty map.
     */
    private final Map<String, String> params;

    private AuthorizationConfig(Builder builder) {
        this.params = Collections.unmodifiableMap(builder.params);
    }

    /**
     * Returns implementation-specific connection parameters; never null.
     *
     * @return implementation-specific connection parameters; never null
     */
    public Map<String, String> getParams() { return params; }

    /**
     * Returns a new builder for an {@link AuthorizationConfig}.
     *
     * @return a new builder for an {@link AuthorizationConfig}
     */
    public static Builder builder() { return new Builder(); }

    /** Builder for {@link AuthorizationConfig}. */
    public static final class Builder {
        private Map<String, String> params = Collections.emptyMap();

        /** Constructs a new, empty builder. */
        public Builder() {}

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
         * Returns the built {@link AuthorizationConfig}.
         *
         * @return the built {@link AuthorizationConfig}
         */
        public AuthorizationConfig build() {
            return new AuthorizationConfig(this);
        }
    }
}
