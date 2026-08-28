package dev.itara.spi.serializer;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's serializer.
 *
 * <p>Constructed by the agent at startup from the wiring configuration and
 * passed to the serializer factory's {@code parseConfig()}. The agent owns
 * the translation from the wiring config format — implementations receive
 * this type only and are decoupled from the wiring config structure.
 *
 * <p>Unlike {@code TransportConfig}, there is no {@code handleTimeout} or
 * {@code virtualNodeAddress} here — those are transport/topology concerns
 * that do not apply to a serializer.
 */
public final class SerializerConfig {

    /**
     * Serializer-specific connection parameters from the wiring config params
     * block. The keys and values are serializer-defined. Never null — an
     * absent params block yields an empty map.
     */
    private final Map<String, String> params;

    private SerializerConfig(Builder builder) {
        this.params = Collections.unmodifiableMap(builder.params);
    }

    /** @return serializer-specific connection parameters; never null */
    public Map<String, String> getParams() { return params; }

    /** @return a new builder for a {@link SerializerConfig} */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Map<String, String> params = Collections.emptyMap();

        /** @param params serializer-specific connection parameters; null is treated as empty */
        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        /** @return the built {@link SerializerConfig} */
        public SerializerConfig build() {
            return new SerializerConfig(this);
        }
    }
}
