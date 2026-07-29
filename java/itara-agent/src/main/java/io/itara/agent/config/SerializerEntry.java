package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.Collections;
import java.util.Map;

/**
 * The serializer block of a connection entry in the wiring config.
 *
 * Example YAML:
 *
 *   serializer:
 *     id: json
 *     params:
 *       schemaRegistryUrl: "${SCHEMA_REGISTRY_URL:-http://localhost:8081}"
 *
 * The params map is passed to the serializer implementation as-is.
 * The wiring config has no schema for it and no knowledge of what
 * any serializer expects — mirrors how the transport block's params
 * work (see TransportEntry).
 *
 * The id must match the type() identifier of an ItaraSerializer
 * implementation present in itara.lib.dir.
 *
 * This block, and its id in particular, is required on every connection
 * declaration except direct (colocated) connections — a direct connection
 * never crosses a process boundary, so nothing on it is ever serialized,
 * and no serializer choice would mean anything. For every other
 * connection there is no serializer that is safe to assume silently, so
 * ConnectionEntry.validate() rejects a missing block or a missing id
 * within it as a configuration error rather than falling back to a
 * default.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializerEntry {

    private String id;

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    public String getId()        { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }
}
