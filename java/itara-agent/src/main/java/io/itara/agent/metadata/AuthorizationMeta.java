package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [authorization] section of an authorization `.itara` metadata file.
 *
 * Only present when artifact.kind = "authorization". Ignored for all other
 * artifact kinds, by convention only — same caveat as TransportMeta and
 * SerializerMeta: nothing in this class or ItaraMetadataIndex enforces the
 * restriction; that is tooling's job (the CLI).
 *
 * Example TOML:
 *
 *   [authorization]
 *   type = "rbac"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizationMeta {

    /**
     * The authorization mechanism category, e.g. "rbac", "opa", "noop".
     * Distinct from artifact.id, which is the unique identifier of a
     * specific implementation artifact.
     */
    private String type;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Override
    public String toString() {
        return "AuthorizationMeta{type='" + type + "'}";
    }
}
