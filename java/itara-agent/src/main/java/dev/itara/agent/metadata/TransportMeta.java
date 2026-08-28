package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [transport] section of a transport `.itara` metadata file.
 *
 * <p>Only present when artifact.kind = "transport". Ignored for all other
 * artifact kinds.
 *
 * <p>Example TOML:
 * <pre>{@code
 * [transport]
 * type = "http"
 *
 * [transport.capabilities]
 * native-call-timeout = true
 * externally-interruptible = true
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportMeta {

    /**
     * The transport category — describes the communication protocol.
     * Examples: "http", "kafka", "amqp".
     *
     * Two implementations with the same type are considered compatible
     * caller/callee pairs. Distinct from artifact.id, which is the unique
     * identifier of a specific implementation.
     */
    private String type;

    private TransportCapabilities capabilities = TransportCapabilities.defaults();

    /** @return the transport category, e.g. "http" */
    public String getType()                        { return type; }
    /** @param type the transport category, e.g. "http" */
    public void setType(String type)               { this.type = type; }

    /** @return this transport's declared capabilities */
    public TransportCapabilities getCapabilities() { return capabilities; }
    /** @param c this transport's declared capabilities; null falls back to {@link TransportCapabilities#defaults()} */
    public void setCapabilities(TransportCapabilities c) {
        this.capabilities = c != null ? c : TransportCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "TransportMeta{type='" + type + "', capabilities=" + capabilities + "}";
    }
}
