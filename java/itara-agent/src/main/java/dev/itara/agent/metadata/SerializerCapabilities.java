package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * The [serializer.capabilities] section of a serializer `.itara` metadata
 * file.
 *
 * <p>Declares which message formats this serializer implementation handles
 * beyond plain, hand-written types — e.g. a protobuf serializer declares
 * "protobuf" here, meaning it can generically handle proto-generated
 * types via reflection (ADR 0019).
 *
 * <p>Unlike TransportCapabilities, which defaults permissively (true) since
 * a transport is assumed capable unless it says otherwise, this defaults
 * to an empty list when the section is absent — a serializer is assumed
 * to handle plain types only until it explicitly declares a structural
 * message format it supports.
 *
 * <p>This has no bearing on error-payload handling, which is unconditional
 * for every serializer regardless of declared message-formats (ADR 0020).
 *
 * <p>Example TOML:
 * <pre>{@code
 * [serializer.capabilities]
 * message-formats = ["protobuf"]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializerCapabilities {

    /** Required for deserialization. */
    public SerializerCapabilities() {}

    @JsonProperty("message-formats")
    private List<String> messageFormats = Collections.emptyList();

    /**
     * Returns the structural message formats this serializer handles; never null.
     *
     * @return the structural message formats this serializer handles; never null
     */
    public List<String> getMessageFormats() { return messageFormats; }
    /**
     * Sets the structural message formats this serializer handles; null is treated as empty.
     *
     * @param messageFormats the structural message formats this serializer handles; null is treated as empty
     */
    public void setMessageFormats(List<String> messageFormats) {
        this.messageFormats = messageFormats != null ? messageFormats : Collections.emptyList();
    }

    /**
     * Returns a capabilities instance with an empty message-formats list.
     *
     * <p>Used when the [serializer.capabilities] section is absent from the file.
     *
     * @return a capabilities instance with an empty message-formats list
     */
    public static SerializerCapabilities defaults() {
        return new SerializerCapabilities();
    }

    @Override
    public String toString() {
        return "SerializerCapabilities{messageFormats=" + messageFormats + "}";
    }
}
