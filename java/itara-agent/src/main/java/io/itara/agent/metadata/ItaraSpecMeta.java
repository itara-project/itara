package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The optional [itara] section of a `.itara` metadata file — versions
 * of the Itara spec / core the artifact was built against.
 *
 * Named ItaraSpecMeta (rather than "ItaraMeta") to avoid confusion with
 * MetadataFile, which represents the whole `.itara` document.
 *
 *   [itara]
 *   spec-version = "0.1"
 *   core-version = "0.1+"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItaraSpecMeta {

    @JsonProperty("spec-version")
    private String specVersion = "";

    @JsonProperty("core-version")
    private String coreVersion = "";

    public String getSpecVersion() { return specVersion; }
    public void setSpecVersion(String specVersion) { this.specVersion = specVersion; }

    public String getCoreVersion() { return coreVersion; }
    public void setCoreVersion(String coreVersion) { this.coreVersion = coreVersion; }

    @Override
    public String toString() {
        return "ItaraSpecMeta{specVersion='" + specVersion + "', coreVersion='" + coreVersion + "'}";
    }
}
