package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [authentication] section of an authentication `.itara` metadata file.
 *
 * Only present when artifact.kind = "authentication". Ignored for all other
 * artifact kinds, by convention only — same caveat as TransportMeta and
 * SerializerMeta: nothing in this class or ItaraMetadataIndex enforces the
 * restriction; that is tooling's job (the CLI).
 *
 * Example TOML:
 *
 *   [authentication]
 *   type = "mtls"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthenticationMeta {

    /**
     * The authentication mechanism category, e.g. "mtls", "jwt", "noop".
     * Distinct from artifact.id, which is the unique identifier of a
     * specific implementation artifact.
     */
    private String type;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Override
    public String toString() {
        return "AuthenticationMeta{type='" + type + "'}";
    }
}
