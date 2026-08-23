package io.itara.spi.authorization;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's authorization implementation.
 * Constructed by the agent from the wiring config and passed to
 * {@link ItaraAuthorizationFactory#parseConfig}. Mirrors SerializerConfig.
 */
public final class AuthorizationConfig {

    private final Map<String, String> params;

    private AuthorizationConfig(Builder builder) {
        this.params = Collections.unmodifiableMap(builder.params);
    }

    public Map<String, String> getParams() { return params; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Map<String, String> params = Collections.emptyMap();

        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        public AuthorizationConfig build() {
            return new AuthorizationConfig(this);
        }
    }
}
