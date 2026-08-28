package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the [serializers] section of an API artifact's
 * `.itara` metadata file.
 *
 * <p>Declares one serializer this API artifact was compiled with support
 * for. The id matches a serializer's artifact.id (e.g. "json",
 * "protobuf"); the version is a semver range checked against that
 * serializer's own artifact.version.
 *
 * <p>Neither field is validated here — this class only carries the
 * declared data. Checking a version range's syntax, and evaluating it
 * against an actual serializer's version, is tooling's job (the CLI),
 * not the agent's — the agent has no semver library and does not need
 * one for this.
 *
 * <p>Example TOML — one entry in the {@code supported} array under
 * {@code [serializers]} (see {@link SerializersMeta}):
 * <pre>{@code
 * [serializers]
 * supported = [
 *   { id = "json", version = "1.x" },
 * ]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupportedSerializer {

    /** Matches the artifact.id of a serializer implementation. */
    private String id;

    /** Version range this artifact was compiled/verified against. */
    private String version;

    /** @return the serializer artifact id this dependency targets */
    public String getId()            { return id; }
    /** @param id the serializer artifact id this dependency targets */
    public void setId(String id)     { this.id = id; }

    /** @return the semver range this artifact was compiled/verified against */
    public String getVersion()       { return version; }
    /** @param v the semver range this artifact was compiled/verified against */
    public void setVersion(String v) { this.version = v; }

    @Override
    public String toString() {
        return "SupportedSerializer{id='" + id + "', version='" + version + "'}";
    }
}
