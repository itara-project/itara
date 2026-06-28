package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    @JsonProperty("implemented-event-contracts")
    private ImplementedEventContractsMeta implementedEventContracts = ImplementedEventContractsMeta.ofEmpty();
    @JsonProperty("methods")
    private MethodsMeta methods = MethodsMeta.ofEmpty();
    private TransportMeta transport;


    public ArtifactMeta getArtifact() { return artifact; }
    public void setArtifact(ArtifactMeta artifact) { this.artifact = artifact; }

    public RuntimeMeta getRuntime() { return runtime; }
    public void setRuntime(RuntimeMeta runtime) { this.runtime = runtime; }

    public ItaraSpecMeta getItara() { return itara; }
    public void setItara(ItaraSpecMeta itara) { this.itara = itara; }

    public SerializersMeta getSerializers() { return serializers; }
    public void setSerializers(SerializersMeta serializers) { this.serializers = serializers; }

    public ImplementedEventContractsMeta getImplementedEventContracts() {
        return implementedEventContracts;
    }
    public void setImplementedEventContracts(ImplementedEventContractsMeta m) {
        this.implementedEventContracts = m;
    }

    public MethodsMeta getMethods() { return methods; }
    public void setMethods(MethodsMeta methods) {
        this.methods = methods != null ? methods : MethodsMeta.ofEmpty();
    }

    public TransportMeta getTransport()              { return transport; }
    public void setTransport(TransportMeta transport){ this.transport = transport; }

    @Override
    public String toString() {
        return "MetadataFile{artifact=" + artifact + ", runtime=" + runtime
                + ", itara=" + itara + ", serializers=" + serializers
                + ", transport=" + transport
                + ", implementedEventContracts=" + implementedEventContracts + "}";
    }
}
