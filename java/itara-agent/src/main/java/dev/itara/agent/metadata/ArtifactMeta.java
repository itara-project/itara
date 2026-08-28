package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The [artifact] section of a `.itara` metadata file.
 *
 * <p>Mirrors the Rust `ArtifactMeta` struct (itara-libdir crate / ADR 0008).
 *
 * <p>Example:
 * <pre>{@code
 * [artifact]
 * kind = "component"
 * id = "inventory"
 * version = "1.0.0"
 * api-version = "1.x"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactMeta {

    /** kind = "component" | "api" | "transport" | "serializer" | "observer" | "failure-semantics" |
     * "context-handler" | "authentication" | "authorization" */
    private String kind;

    /**
     * Component id for components and apis (e.g. "inventory").
     * SPI implementation name for transports/serializers/observers (e.g. "http").
     */
    private String id;

    private String version = "";

    @JsonProperty("api-version")
    private String apiVersion = "";

    /** @return the artifact kind, e.g. "component", "api", "transport" */
    public String getKind() { return kind; }
    /** @param kind the artifact kind, e.g. "component", "api", "transport" */
    public void setKind(String kind) { this.kind = kind; }

    /** @return the component id or SPI implementation name this artifact declares */
    public String getId() { return id; }
    /** @param id the component id or SPI implementation name this artifact declares */
    public void setId(String id) { this.id = id; }

    /** @return this artifact's own version */
    public String getVersion() { return version; }
    /** @param version this artifact's own version */
    public void setVersion(String version) { this.version = version; }

    /** @return the API version this artifact exposes or was compiled against */
    public String getApiVersion() { return apiVersion; }
    /** @param apiVersion the API version this artifact exposes or was compiled against */
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    @Override
    public String toString() {
        return "ArtifactMeta{kind='" + kind + "', id='" + id
                + "', version='" + version + "', apiVersion='" + apiVersion + "'}";
    }
}
