package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.itara.spi.transport.TransportConfig;

import java.util.Collections;
import java.util.Map;

/**
 * The transport block of a connection entry in the wiring config.
 *
 * Example YAML:
 *
 *   transport:
 *     id: http
 *     handleTimeout: true
 *     params:
 *       host: "${CALC_HOST:-localhost}"
 *       port: "8081"
 *
 * The params map is passed to the transport implementation as-is.
 * The wiring config has no schema for it and no knowledge of what
 * any transport expects.
 *
 * handleTimeout declares whether the transport should enforce the
 * per-attempt timeout natively for this connection (§14.10). The
 * timeout value itself lives in the failureSemantics block and is
 * passed to the transport on every call via TransportCall.call(timeout).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportEntry {

    /** The transport type identifier. Required. */
    private String id;

    /**
     * Whether the transport should enforce the per-attempt timeout
     * natively for this connection (§14.10).
     */
    private boolean handleTimeout = false;

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    public String getId()                  { return id; }
    public void setId(String id)           { this.id = id; }

    public boolean isHandleTimeout()       { return handleTimeout; }
    public void setHandleTimeout(boolean h){ this.handleTimeout = h; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }
}
