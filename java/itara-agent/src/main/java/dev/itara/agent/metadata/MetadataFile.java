package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed contents of a single `.itara` metadata file.
 *
 * <p>Mirrors the Rust `MetadataFile` struct (itara-libdir crate / ADR 0008).
 * Only the [artifact] section is required. Every other section — [runtime],
 * [itara], [serializers], [contract], [serializer], [transport],
 * [methods], [failure-semantics], [authentication], [authorization],
 * [api-dependencies], and [implemented-event-contracts] — is optional,
 * and only meaningful for certain artifact.kind values (see each
 * section's own class). Unknown sections and fields are ignored for
 * forward compatibility.
 *
 * <pre>{@code
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
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataFile {

    /** Required for deserialization. */
    public MetadataFile() {}

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


    /**
     * Returns the required [artifact] section, or null if not yet set.
     *
     * @return the required [artifact] section, or null if not yet set
     */
    public ArtifactMeta getArtifact() { return artifact; }
    /**
     * Sets the required [artifact] section.
     *
     * @param artifact the required [artifact] section
     */
    public void setArtifact(ArtifactMeta artifact) { this.artifact = artifact; }

    /**
     * Returns the optional [runtime] section, or null if absent.
     *
     * @return the optional [runtime] section, or null if absent
     */
    public RuntimeMeta getRuntime() { return runtime; }
    /**
     * Sets the optional [runtime] section.
     *
     * @param runtime the optional [runtime] section
     */
    public void setRuntime(RuntimeMeta runtime) { this.runtime = runtime; }

    /**
     * Returns the optional [itara] section, or null if absent.
     *
     * @return the optional [itara] section, or null if absent
     */
    public ItaraSpecMeta getItara() { return itara; }
    /**
     * Sets the optional [itara] section.
     *
     * @param itara the optional [itara] section
     */
    public void setItara(ItaraSpecMeta itara) { this.itara = itara; }

    /**
     * Returns the optional [serializers] section, or null if absent.
     *
     * @return the optional [serializers] section, or null if absent
     */
    public SerializersMeta getSerializers() { return serializers; }
    /**
     * Sets the optional [serializers] section.
     *
     * @param serializers the optional [serializers] section
     */
    public void setSerializers(SerializersMeta serializers) { this.serializers = serializers; }

    /**
     * Returns the optional [implemented-event-contracts] section; never null.
     *
     * @return the optional [implemented-event-contracts] section; never null
     */
    public ImplementedEventContractsMeta getImplementedEventContracts() {
        return implementedEventContracts;
    }
    /**
     * Sets the optional [implemented-event-contracts] section.
     *
     * @param m the optional [implemented-event-contracts] section
     */
    public void setImplementedEventContracts(ImplementedEventContractsMeta m) {
        this.implementedEventContracts = m;
    }

    /**
     * Returns the optional [methods] section; never null.
     *
     * @return the optional [methods] section; never null
     */
    public MethodsMeta getMethods() { return methods; }
    /**
     * Sets the optional [methods] section; null falls back to {@link MethodsMeta#ofEmpty()}.
     *
     * @param methods the optional [methods] section; null falls back to {@link MethodsMeta#ofEmpty()}
     */
    public void setMethods(MethodsMeta methods) {
        this.methods = methods != null ? methods : MethodsMeta.ofEmpty();
    }

    /**
     * Returns the optional [transport] section, or null if absent.
     *
     * @return the optional [transport] section, or null if absent
     */
    public TransportMeta getTransport()              { return transport; }
    /**
     * Sets the optional [transport] section.
     *
     * @param transport the optional [transport] section
     */
    public void setTransport(TransportMeta transport){ this.transport = transport; }

    /**
     * Returns the optional [failure-semantics] section, or null if absent.
     *
     * @return the optional [failure-semantics] section, or null if absent
     */
    public FailureSemanticsMeta getFailureSemantics()               { return failureSemantics; }
    /**
     * Sets the optional [failure-semantics] section.
     *
     * @param fs the optional [failure-semantics] section
     */
    public void setFailureSemantics(FailureSemanticsMeta fs)        { this.failureSemantics = fs; }

    /**
     * Returns the optional [api-dependencies] section, or null if absent.
     *
     * @return the optional [api-dependencies] section, or null if absent
     */
    public ApiDependenciesMeta getApiDependencies()                 { return apiDependencies; }
    /**
     * Sets the optional [api-dependencies] section.
     *
     * @param a the optional [api-dependencies] section
     */
    public void setApiDependencies(ApiDependenciesMeta a)           { this.apiDependencies = a; }

    /**
     * Returns the optional [contract] section, or null if absent.
     *
     * @return the optional [contract] section, or null if absent
     */
    public ContractMeta getContract()               { return contract; }
    /**
     * Sets the optional [contract] section.
     *
     * @param contract the optional [contract] section
     */
    public void setContract(ContractMeta contract)  { this.contract = contract; }

    /**
     * Returns the optional [serializer] section, or null if absent.
     *
     * @return the optional [serializer] section, or null if absent
     */
    public SerializerMeta getSerializer()               { return serializer; }
    /**
     * Sets the optional [serializer] section.
     *
     * @param serializer the optional [serializer] section
     */
    public void setSerializer(SerializerMeta serializer){ this.serializer = serializer; }

    /**
     * Returns the optional [authentication] section, or null if absent.
     *
     * @return the optional [authentication] section, or null if absent
     */
    public AuthenticationMeta getAuthentication()                  { return authentication; }
    /**
     * Sets the optional [authentication] section.
     *
     * @param authentication the optional [authentication] section
     */
    public void setAuthentication(AuthenticationMeta authentication) { this.authentication = authentication; }

    /**
     * Returns the optional [authorization] section, or null if absent.
     *
     * @return the optional [authorization] section, or null if absent
     */
    public AuthorizationMeta getAuthorization()                    { return authorization; }
    /**
     * Sets the optional [authorization] section.
     *
     * @param authorization the optional [authorization] section
     */
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
