package dev.itara.spi.authentication;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's authentication implementation.
 *
 * <p>Constructed by the agent at startup from the wiring configuration and
 * passed to {@link ItaraAuthenticationFactory#parseConfig}. The agent owns
 * the translation from the wiring config format — implementations receive
 * this type only and are decoupled from the wiring config structure.
 */
public final class AuthenticationConfig {

    /**
     * Implementation-specific parameters from the wiring config params block.
     * The keys and values are implementation-defined. Never null — an
     * absent params block yields an empty map.
     */
    private final Map<String, String> params;

    private AuthenticationConfig(Builder builder) {
        this.params = Collections.unmodifiableMap(builder.params);
    }

    /**
     * Returns parameters.
     *
     * @return implementation-specific connection parameters; never null */
    public Map<String, String> getParams() { return params; }

    /**
     * Returns a new builder.
     *
     * @return a new builder for an {@link AuthenticationConfig} */
    public static Builder builder() { return new Builder(); }

    /** Builder for {@link AuthenticationConfig}. */
    public static final class Builder {
        private Map<String, String> params = Collections.emptyMap();

        /** @param params implementation-specific connection parameters; null is treated as empty */
        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        /** @return the built {@link AuthenticationConfig} */
        public AuthenticationConfig build() {
            return new AuthenticationConfig(this);
        }
    }
}
