package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [transport.capabilities] section of a transport `.itara` metadata file.
 *
 * <p>Declares what the transport implementation is capable of, so the agent
 * and failure semantics layer can make informed decisions without probing
 * the transport at runtime.
 *
 * <p>Defaults to true for both fields when the section is absent — the
 * assumption is that a transport is capable unless it declares otherwise.
 *
 * <p>Example TOML:
 * <pre>{@code
 * [transport.capabilities]
 * native-call-timeout = true
 * externally-interruptible = false
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportCapabilities {

    /** Required for deserialization. */
    public TransportCapabilities() {}

    /**
     * Whether this transport can enforce the per-call timeout natively —
     * i.e. abort an in-flight call when the timeout passed to send() expires.
     * If false, the failure semantics layer is responsible for external
     * enforcement (§14.10).
     */
    @JsonProperty("native-call-timeout")
    private boolean nativeCallTimeout = true;

    /**
     * Whether this transport is safe to interrupt externally on timeout —
     * i.e. the thread blocked in send() can be interrupted without leaving
     * the transport in a broken state.
     */
    @JsonProperty("externally-interruptible")
    private boolean externallyInterruptible = true;

    /**
     * Returns whether this transport can enforce the per-call timeout natively.
     *
     * @return whether this transport can enforce the per-call timeout natively
     */
    public boolean isNativeCallTimeout()       { return nativeCallTimeout; }
    /**
     * Sets whether this transport can enforce the per-call timeout natively.
     *
     * @param v whether this transport can enforce the per-call timeout natively
     */
    public void setNativeCallTimeout(boolean v){ this.nativeCallTimeout = v; }

    /**
     * Returns whether this transport is safe to interrupt externally on timeout.
     *
     * @return whether this transport is safe to interrupt externally on timeout
     */
    public boolean isExternallyInterruptible()       { return externallyInterruptible; }
    /**
     * Sets whether this transport is safe to interrupt externally on timeout.
     *
     * @param v whether this transport is safe to interrupt externally on timeout
     */
    public void setExternallyInterruptible(boolean v){ this.externallyInterruptible = v; }

    /**
     * Returns a capabilities instance with both fields set to their defaults.
     * Used when the [transport.capabilities] section is absent from the file.
     *
     * @return a capabilities instance with both fields set to their defaults
     */
    public static TransportCapabilities defaults() {
        return new TransportCapabilities();
    }

    @Override
    public String toString() {
        return "TransportCapabilities{nativeCallTimeout=" + nativeCallTimeout
                + ", externallyInterruptible=" + externallyInterruptible + "}";
    }
}
