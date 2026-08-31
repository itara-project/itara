package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [contract] section of a contract-bearing artifact's `.itara`
 * metadata file.
 *
 * <p>Meaningful for artifact.kind = "api" and artifact.kind = "events" —
 * ignored for all other artifact kinds. Nothing in this class or in
 * ItaraMetadataIndex enforces that restriction; it is a documentation
 * contract, same as TransportMeta being "only meaningful for kind =
 * transport". Enforcing it is left to tooling (the CLI).
 *
 * <p>Declares the message format the contract's method parameter and
 * return types are generated from — e.g. "protobuf" (ADR 0019). This is
 * a structural property of the contract's own types, unrelated to which
 * serializer ids the artifact is compatible with (see SerializersMeta) —
 * message format and serializer choice vary independently.
 *
 * <p>Example TOML:
 * <pre>{@code
 * [contract]
 * message-format = "protobuf"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractMeta {

    /** Required for deserialization. */
    public ContractMeta() {}

    /**
     * The declared message format, e.g. "protobuf". Empty string is
     * treated identically to the section being absent entirely — both
     * mean the contract uses plain, hand-written types. Never null.
     */
    @JsonProperty("message-format")
    private String messageFormat = "";

    /**
     * Returns the declared message format, or empty string if none is declared.
     *
     * @return the declared message format, or empty string if none is declared
     */
    public String getMessageFormat() { return messageFormat; }
    /**
     * Sets the declared message format; null is treated as empty.
     *
     * @param messageFormat the declared message format; null is treated as empty
     */
    public void setMessageFormat(String messageFormat) {
        this.messageFormat = messageFormat != null ? messageFormat : "";
    }

    /**
     * Returns true if this contract declares a message format other than
     * plain hand-written types.
     *
     * <p>False for both an absent [contract] section and an explicit
     * empty-string declaration — callers should use this rather than
     * checking getMessageFormat() directly, so the absent/empty
     * equivalence doesn't have to be remembered at every call site.
     *
     * @return true if this contract declares a message format
     */
    public boolean hasMessageFormat() {
        return !messageFormat.isBlank();
    }

    @Override
    public String toString() {
        return "ContractMeta{messageFormat='" + messageFormat + "'}";
    }
}
