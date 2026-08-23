package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed contents of a single `.itara` metadata file.
 *
 * Mirrors the Rust `MetadataFile` struct (itara-libdir crate / ADR 0008).
 * Only the [artifact] section is required; [runtime], [itara],
 * [serializers], [contract], and [serializer] are optional. Unknown
 * sections and fields are ignored for forward compatibility.
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
    @JsonProperty("failure-semantics")
    private FailureSemanticsMeta failureSemantics;
    @JsonProperty("api-dependencies")
    private ApiDependenciesMeta apiDependencies;
    private ContractMeta contract;
    private SerializerMeta serializer;
    private AuthenticationMeta authentication;
    private AuthorizationMeta authorization;


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

    public FailureSemanticsMeta getFailureSemantics()               { return failureSemantics; }
    public void setFailureSemantics(FailureSemanticsMeta fs)        { this.failureSemantics = fs; }

    public ApiDependenciesMeta getApiDependencies()                 { return apiDependencies; }
    public void setApiDependencies(ApiDependenciesMeta a)           { this.apiDependencies = a; }

    public ContractMeta getContract()               { return contract; }
    public void setContract(ContractMeta contract)  { this.contract = contract; }

    public SerializerMeta getSerializer()               { return serializer; }
    public void setSerializer(SerializerMeta serializer){ this.serializer = serializer; }

    public AuthenticationMeta getAuthentication()                  { return authentication; }
    public void setAuthentication(AuthenticationMeta authentication) { this.authentication = authentication; }

    public AuthorizationMeta getAuthorization()                    { return authorization; }
    public void setAuthorization(AuthorizationMeta authorization)  { this.authorization = authorization; }

    @Override
    public String toString() {
        return "MetadataFile{artifact=" + artifact + ", runtime=" + runtime
                + ", itara=" + itara + ", serializers=" + serializers
                + ", contract=" + contract
                + ", serializer=" + serializer
                + ", transport=" + transport
                + ", failureSemantics=" + failureSemantics
                + ", authentication=" + authentication
                + ", authorization=" + authorization
                + ", apiDependencies=" + apiDependencies
                + ", implementedEventContracts=" + implementedEventContracts + "}";
    }
}
