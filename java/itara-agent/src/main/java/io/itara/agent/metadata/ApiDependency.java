package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the [api-dependencies] section of a component
 * `.itara` metadata file.
 *
 * Declares one synchronous API contract this component was compiled
 * against. The id matches the artifact.id of the callee's kind = "api"
 * `.itara` file. The version is the exact version the component was
 * built against — the tool uses this to verify compatibility against
 * the callee's declared api-version.
 *
 * Example TOML:
 *
 *   [[api-dependencies.calls]]
 *   id = "calculator"
 *   version = "1.0.0"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiDependency {

    /** Matches artifact.id of the callee's kind = "api" artifact. */
    private String id;

    /** Exact version this component was compiled against. */
    private String version;

    public String getId()              { return id; }
    public void setId(String id)       { this.id = id; }

    public String getVersion()         { return version; }
    public void setVersion(String v)   { this.version = v; }

    @Override
    public String toString() {
        return "ApiDependency{id='" + id + "', version='" + version + "'}";
    }
}
