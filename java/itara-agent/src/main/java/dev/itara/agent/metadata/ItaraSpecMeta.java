package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The optional [itara] section of a `.itara` metadata file — versions
 * of the Itara spec / core the artifact was built against.
 *
 * <p>Named ItaraSpecMeta (rather than "ItaraMeta") to avoid confusion with
 * MetadataFile, which represents the whole `.itara` document.
 *
 * <pre>{@code
 * [itara]
 * spec-version = "0.1"
 * core-version = "0.1+"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItaraSpecMeta {

    @JsonProperty("spec-version")
    private String specVersion = "";

    @JsonProperty("core-version")
    private String coreVersion = "";

    /** @return the Itara spec version this artifact was built against */
    public String getSpecVersion() { return specVersion; }
    /** @param specVersion the Itara spec version this artifact was built against */
    public void setSpecVersion(String specVersion) { this.specVersion = specVersion; }

    /** @return the Itara core version this artifact was built against */
    public String getCoreVersion() { return coreVersion; }
    /** @param coreVersion the Itara core version this artifact was built against */
    public void setCoreVersion(String coreVersion) { this.coreVersion = coreVersion; }

    @Override
    public String toString() {
        return "ItaraSpecMeta{specVersion='" + specVersion + "', coreVersion='" + coreVersion + "'}";
    }
}
