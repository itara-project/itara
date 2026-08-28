package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [authorization] section of an authorization `.itara` metadata file.
 *
 * <p>Only present when artifact.kind = "authorization". Ignored for all other
 * artifact kinds, by convention only — same caveat as TransportMeta and
 * SerializerMeta: nothing in this class or ItaraMetadataIndex enforces the
 * restriction; that is tooling's job (the CLI).
 *
 * <p>Example TOML:
 * <pre>{@code
 * [authorization]
 * type = "rbac"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizationMeta {

    /**
     * The authorization mechanism category, e.g. "rbac", "opa", "noop".
     * Distinct from artifact.id, which is the unique identifier of a
     * specific implementation artifact.
     */
    private String type;

    /** @return the authorization mechanism category, e.g. "rbac", "opa" */
    public String getType() { return type; }
    /** @param type the authorization mechanism category, e.g. "rbac", "opa" */
    public void setType(String type) { this.type = type; }

    @Override
    public String toString() {
        return "AuthorizationMeta{type='" + type + "'}";
    }
}
