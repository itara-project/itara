package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [transport] section of a transport `.itara` metadata file.
 *
 * Only present when artifact.kind = "transport". Ignored for all other
 * artifact kinds.
 *
 * Example TOML:
 *
 *   [transport]
 *   type = "http"
 *
 *   [transport.capabilities]
 *   nativeCallTimeout = true
 *   externallyInterruptible = true
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

    public String getType()                        { return type; }
    public void setType(String type)               { this.type = type; }

    public TransportCapabilities getCapabilities() { return capabilities; }
    public void setCapabilities(TransportCapabilities c) {
        this.capabilities = c != null ? c : TransportCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "TransportMeta{type='" + type + "', capabilities=" + capabilities + "}";
    }
}
