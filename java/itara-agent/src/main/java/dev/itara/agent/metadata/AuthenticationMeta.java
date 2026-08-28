package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The [authentication] section of an authentication `.itara` metadata file.
 *
 * <p>Only present when artifact.kind = "authentication". Ignored for all other
 * artifact kinds, by convention only — same caveat as TransportMeta and
 * SerializerMeta: nothing in this class or ItaraMetadataIndex enforces the
 * restriction; that is tooling's job (the CLI).
 *
 * <p>Example TOML:
 * <pre>{@code
 *   [authentication]
 *   type = "mtls"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthenticationMeta {

    /**
     * The authentication mechanism category, e.g. "mtls", "jwt", "noop".
     * Distinct from artifact.id, which is the unique identifier of a
     * specific implementation artifact.
     */
    private String type;

    /** @return the authentication mechanism category, e.g. "mtls", "jwt" */
    public String getType() { return type; }
    /** @param type the authentication mechanism category, e.g. "mtls", "jwt" */
    public void setType(String type) { this.type = type; }

    @Override
    public String toString() {
        return "AuthenticationMeta{type='" + type + "'}";
    }
}
