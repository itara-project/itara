package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [artifact] section of a `.itara` metadata file.
 *
 * Mirrors the Rust `ArtifactMeta` struct (itara-libdir crate / ADR 0008).
 *
 * Example:
 *
 *   [artifact]
 *   kind = "component"
 *   id = "inventory"
 *   version = "1.0.0"
 *   api-version = "1.x"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactMeta {

    /** kind = "component" | "api" | "transport" | "serializer" | "observer" | "context-handler" */
    private String kind;

    /**
     * Component id for components and apis (e.g. "inventory").
     * SPI implementation name for transports/serializers/observers (e.g. "http").
     */
    private String id;

    private String version = "";

    @JsonProperty("api-version")
    private String apiVersion = "";

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    @Override
    public String toString() {
        return "ArtifactMeta{kind='" + kind + "', id='" + id
                + "', version='" + version + "', apiVersion='" + apiVersion + "'}";
    }
}
