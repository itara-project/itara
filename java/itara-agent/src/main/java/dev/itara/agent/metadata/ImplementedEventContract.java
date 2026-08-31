package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the [implemented-event-contracts] section of a
 * component's .itara metadata file.
 *
 * <p>Declares that this component implements a specific event contract
 * at a specific version of the events artifact. Version compatibility
 * is checked by the agent at startup and by `itara verify` at build
 * time using semver rules.
 *
 * <p>Example .itara entry:
 *   { id = "order-events/order-placed", version = "1.0.0" }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImplementedEventContract {

    /** Required for deserialization. */
    public ImplementedEventContract() {}

    /** Full contract reference: "<collection-id>/<contract-id>" */
    private String id;

    /** Version of the events artifact this implementation was written against. */
    private String version;

    /**
     * Returns the full contract reference this component implements.
     *
     * @return the full contract reference this component implements
     */
    public String getId()      { return id; }
    /**
     * Sets the full contract reference this component implements.
     *
     * @param id the full contract reference this component implements
     */
    public void setId(String id) { this.id = id; }

    /**
     * Returns the version of the events artifact this was written against.
     *
     * @return the version of the events artifact this was written against
     */
    public String getVersion()           { return version; }
    /**
     * Sets the version of the events artifact this was written against.
     *
     * @param version the version of the events artifact this was written against
     */
    public void setVersion(String version) { this.version = version; }

    @Override
    public String toString() {
        return "ImplementedEventContract{id='" + id + "', version='" + version + "'}";
    }
}
