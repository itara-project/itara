package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [contract] section of an API artifact's `.itara` metadata file.
 *
 * Only meaningful when artifact.kind = "api" — ignored for all other
 * artifact kinds, by convention only. Nothing in this class or in
 * ItaraMetadataIndex enforces that restriction; it is a documentation
 * contract, same as TransportMeta being "only meaningful for kind =
 * transport". Enforcing it is left to tooling (the CLI).
 *
 * Declares the message format the contract's method parameter and
 * return types are generated from — e.g. "protobuf" (ADR 0019). This is
 * a structural property of the contract's own types, unrelated to which
 * serializer ids the artifact is compatible with (see SerializersMeta) —
 * message format and serializer choice vary independently.
 *
 * Example TOML:
 *
 *   [contract]
 *   message-format = "protobuf"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractMeta {

    /**
     * The declared message format, e.g. "protobuf". Empty string is
     * treated identically to the section being absent entirely — both
     * mean the contract uses plain, hand-written types. Never null.
     */
    @JsonProperty("message-format")
    private String messageFormat = "";

    public String getMessageFormat() { return messageFormat; }
    public void setMessageFormat(String messageFormat) {
        this.messageFormat = messageFormat != null ? messageFormat : "";
    }

    /**
     * Returns true if this contract declares a message format other than
     * plain hand-written types. False for both an absent [contract]
     * section and an explicit empty-string declaration — callers should
     * use this rather than checking getMessageFormat() directly, so the
     * absent/empty equivalence doesn't have to be remembered at every
     * call site.
     */
    public boolean hasMessageFormat() {
        return !messageFormat.isBlank();
    }

    @Override
    public String toString() {
        return "ContractMeta{messageFormat='" + messageFormat + "'}";
    }
}
