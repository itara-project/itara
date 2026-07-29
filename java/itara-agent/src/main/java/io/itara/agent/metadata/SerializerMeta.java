package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [serializer] section of a serializer `.itara` metadata file.
 *
 * Only present when artifact.kind = "serializer". Ignored for all other
 * artifact kinds, by convention only — same caveat as ContractMeta and
 * TransportMeta: nothing in this class or ItaraMetadataIndex enforces
 * the restriction; that is tooling's job (the CLI).
 *
 * Example TOML:
 *
 *   [serializer]
 *   type = "protobuf"
 *
 *   [serializer.capabilities]
 *   message-formats = ["protobuf"]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializerMeta {

    /**
     * The serialization category — describes what this implementation
     * serializes to/from, e.g. "json", "protobuf". Distinct from
     * artifact.id, which is the unique identifier of a specific
     * implementation.
     */
    private String type;

    private SerializerCapabilities capabilities = SerializerCapabilities.defaults();

    public String getType()                          { return type; }
    public void setType(String type)                 { this.type = type; }

    public SerializerCapabilities getCapabilities()   { return capabilities; }
    public void setCapabilities(SerializerCapabilities c) {
        this.capabilities = c != null ? c : SerializerCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "SerializerMeta{type='" + type + "', capabilities=" + capabilities + "}";
    }
}
