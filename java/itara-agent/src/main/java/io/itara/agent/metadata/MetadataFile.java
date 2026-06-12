package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parsed contents of a single `.itara` metadata file.
 *
 * Mirrors the Rust `MetadataFile` struct (itara-libdir crate / ADR 0008).
 * Only the [artifact] section is required; [runtime], [itara], and
 * [serializers] are optional. Unknown sections and fields are ignored
 * for forward compatibility.
 *
 *   [artifact]
 *   kind = "component"
 *   id = "inventory"
 *   version = "1.0.0"
 *   api-version = "1.x"
 *
 *   [runtime]
 *   language = "java"
 *
 *   [itara]
 *   spec-version = "0.1"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataFile {

    private ArtifactMeta artifact;
    private RuntimeMeta runtime;
    private ItaraSpecMeta itara;
    private SerializersMeta serializers;

    public ArtifactMeta getArtifact() { return artifact; }
    public void setArtifact(ArtifactMeta artifact) { this.artifact = artifact; }

    public RuntimeMeta getRuntime() { return runtime; }
    public void setRuntime(RuntimeMeta runtime) { this.runtime = runtime; }

    public ItaraSpecMeta getItara() { return itara; }
    public void setItara(ItaraSpecMeta itara) { this.itara = itara; }

    public SerializersMeta getSerializers() { return serializers; }
    public void setSerializers(SerializersMeta serializers) { this.serializers = serializers; }

    @Override
    public String toString() {
        return "MetadataFile{artifact=" + artifact + ", runtime=" + runtime
                + ", itara=" + itara + ", serializers=" + serializers + "}";
    }
}
