package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import dev.itara.spi.transport.TransportConfig;

import java.util.Collections;
import java.util.Map;

/**
 * The transport block of a connection entry in the wiring config.
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * transport:
 *   id: http
 *   handleTimeout: true
 *   params:
 *     host: "${CALC_HOST:-localhost}"
 *     port: "8081"
 * }</pre>
 *
 * <p>The params map is passed to the transport implementation as-is.
 * The wiring config has no schema for it and no knowledge of what
 * any transport expects.
 *
 * <p>handleTimeout declares whether the transport should enforce the
 * per-attempt timeout natively for this connection (§14.10). The
 * timeout value itself lives in the failureSemantics block and is
 * passed to the transport on every call via TransportCall.call(timeout).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportEntry {

    /** Required for deserialization. */
    public TransportEntry() {}

    /** The transport type identifier. Required. */
    private String id;

    /**
     * Whether the transport should enforce the per-attempt timeout
     * natively for this connection (§14.10).
     */
    private boolean handleTimeout = false;

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    /**
     * Returns the transport type identifier.
     *
     * @return the transport type identifier
     */
    public String getId()                  { return id; }
    /**
     * Sets the transport type identifier.
     *
     * @param id the transport type identifier
     */
    public void setId(String id)           { this.id = id; }

    /**
     * Returns whether the transport should enforce the per-attempt timeout natively.
     *
     * @return whether the transport should enforce the per-attempt timeout natively
     */
    public boolean isHandleTimeout()       { return handleTimeout; }
    /**
     * Sets whether the transport should enforce the per-attempt timeout natively.
     *
     * @param h whether the transport should enforce the per-attempt timeout natively
     */
    public void setHandleTimeout(boolean h){ this.handleTimeout = h; }

    /**
     * Returns transport-specific parameters; never null.
     *
     * @return transport-specific parameters; never null
     */
    public Map<String, String> getParams() { return params; }
    /**
     * Sets transport-specific parameters; null is treated as empty.
     *
     * @param params transport-specific parameters; null is treated as empty
     */
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }
}
