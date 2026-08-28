package dev.itara.spi.transport;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for a single connection's transport.
 *
 * <p>Constructed by the agent at startup from the wiring configuration and
 * passed to the transport on {@code registerListener()} and {@code send()}.
 * The agent owns the translation from the wiring config format —
 * implementations receive this type only and are decoupled from the
 * wiring config structure.
 *
 * <p>The timeout value itself is not carried here. It flows from the failure
 * semantics configuration through {@code TransportCall.call(timeout)} on
 * every attempt (§14.10). This config only declares whether the transport
 * should enforce that value natively.
 */
public final class TransportConfig {

    /**
     * Whether this transport should enforce the per-attempt timeout natively
     * for this connection (§14.10).
     * False means the transport ignores the timeout passed to send() —
     * enforcement is left to the failure semantics implementation.
     */
    private final boolean handleTimeout;

    /**
     * Transport-specific connection parameters from the wiring config params
     * block. The keys and values are transport-defined. Never null — an absent
     * params block yields an empty map.
     */
    private final Map<String, String> params;

    /**
     * The address of the virtual node on this connection, if any.
     * For Kafka this is the topic name. For other virtual-node-backed
     * transports it is whatever address the virtual node declares.
     * Null if this connection has no virtual node.
     */
    private final String virtualNodeAddress;

    private TransportConfig(Builder builder) {
        this.handleTimeout      = builder.handleTimeout;
        this.params             = Collections.unmodifiableMap(builder.params);
        this.virtualNodeAddress = builder.virtualNodeAddress;
    }

    /** @return whether this connection's transport should enforce the per-attempt timeout natively */
    public boolean isHandleTimeout()       { return handleTimeout; }
    /** @return transport-specific connection parameters; never null */
    public Map<String, String> getParams() { return params; }
    /** @return the virtual node address for this connection, or null if none */
    public String getVirtualNodeAddress()  { return virtualNodeAddress; }

    /** @return a new builder for a {@link TransportConfig} */
    public static Builder builder() { return new Builder(); }

    /** Builder for {@link TransportConfig}. */
    public static final class Builder {
        private boolean handleTimeout = false;
        private Map<String, String> params = Collections.emptyMap();
        private String virtualNodeAddress = null;

        /** @param handleTimeout whether the transport should enforce the per-attempt timeout natively */
        public Builder handleTimeout(boolean handleTimeout) {
            this.handleTimeout = handleTimeout;
            return this;
        }
        /** @param params transport-specific connection parameters; null is treated as empty */
        public Builder params(Map<String, String> params) {
            this.params = (params != null) ? params : Collections.emptyMap();
            return this;
        }
        /** @param virtualNodeAddress the virtual node address for this connection, or null if none */
        public Builder virtualNodeAddress(String virtualNodeAddress) {
            this.virtualNodeAddress = virtualNodeAddress;
            return this;
        }
        /** @return the built {@link TransportConfig} */
        public TransportConfig build() {
            return new TransportConfig(this);
        }
    }
}
