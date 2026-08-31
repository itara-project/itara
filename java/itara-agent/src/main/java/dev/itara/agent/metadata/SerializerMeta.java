package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [serializer] section of a serializer `.itara` metadata file.
 *
 * <p>Only present when artifact.kind = "serializer". Ignored for all other
 * artifact kinds, by convention only — same caveat as ContractMeta and
 * TransportMeta: nothing in this class or ItaraMetadataIndex enforces
 * the restriction; that is tooling's job (the CLI).
 *
 * <p>Example TOML:
 * <pre>{@code
 * [serializer]
 * type = "protobuf"
 *
 * [serializer.capabilities]
 * message-formats = ["protobuf"]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializerMeta {

    /** Required for deserialization. */
    public SerializerMeta() {}

    /**
     * The serialization category — describes what this implementation
     * serializes to/from, e.g. "json", "protobuf". Distinct from
     * artifact.id, which is the unique identifier of a specific
     * implementation.
     */
    private String type;

    private SerializerCapabilities capabilities = SerializerCapabilities.defaults();

    /**
     * Returns the serialization category, e.g. "json", "protobuf".
     *
     * @return the serialization category, e.g. "json", "protobuf"
     */
    public String getType()                          { return type; }
    /**
     * Sets the serialization category, e.g. "json", "protobuf".
     *
     * @param type the serialization category, e.g. "json", "protobuf"
     */
    public void setType(String type)                 { this.type = type; }

    /**
     * Returns this serializer's declared capabilities.
     *
     * @return this serializer's declared capabilities
     */
    public SerializerCapabilities getCapabilities()   { return capabilities; }
    /**
     * Sets this serializer's declared capabilities; null falls back to {@link SerializerCapabilities#defaults()}.
     *
     * @param c this serializer's declared capabilities; null falls back to {@link SerializerCapabilities#defaults()}
     */
    public void setCapabilities(SerializerCapabilities c) {
        this.capabilities = c != null ? c : SerializerCapabilities.defaults();
    }

    @Override
    public String toString() {
        return "SerializerMeta{type='" + type + "', capabilities=" + capabilities + "}";
    }
}
