package io.itara.spi.authentication;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's authentication implementation.
 * Constructed by the agent from the wiring config and passed to
 * {@link ItaraAuthenticationFactory#parseConfig}. Mirrors SerializerConfig.
 */
public final class AuthenticationConfig {

    private final Map<String, String> params;

    private AuthenticationConfig(Builder builder) {
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
        public AuthenticationConfig build() {
            return new AuthenticationConfig(this);
        }
    }
}
