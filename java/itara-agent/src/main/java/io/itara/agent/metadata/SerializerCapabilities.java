package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * The [serializer.capabilities] section of a serializer `.itara` metadata
 * file.
 *
 * Declares which message formats this serializer implementation handles
 * beyond plain, hand-written types — e.g. a protobuf serializer declares
 * "protobuf" here, meaning it can generically handle proto-generated
 * types via reflection (ADR 0019).
 *
 * Unlike TransportCapabilities, which defaults permissively (true) since
 * a transport is assumed capable unless it says otherwise, this defaults
 * to an empty list when the section is absent — a serializer is assumed
 * to handle plain types only until it explicitly declares a structural
 * message format it supports.
 *
 * This has no bearing on error-payload handling, which is unconditional
 * for every serializer regardless of declared message-formats (ADR 0020).
 *
 * Example TOML:
 *
 *   [serializer.capabilities]
 *   message-formats = ["protobuf"]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializerCapabilities {

    @JsonProperty("message-formats")
    private List<String> messageFormats = Collections.emptyList();

    public List<String> getMessageFormats() { return messageFormats; }
    public void setMessageFormats(List<String> messageFormats) {
        this.messageFormats = messageFormats != null ? messageFormats : Collections.emptyList();
    }

    /**
     * Returns a capabilities instance with an empty message-formats list.
     * Used when the [serializer.capabilities] section is absent from the file.
     */
    public static SerializerCapabilities defaults() {
        return new SerializerCapabilities();
    }

    @Override
    public String toString() {
        return "SerializerCapabilities{messageFormats=" + messageFormats + "}";
    }
}
