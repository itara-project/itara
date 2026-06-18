package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the [implemented-event-contracts] section of a
 * component's .itara metadata file.
 *
 * Declares that this component implements a specific event contract
 * at a specific version of the events artifact. Version compatibility
 * is checked by the agent at startup and by `itara verify` at build
 * time using semver rules.
 *
 * Example .itara entry:
 *   { id = "order-events/order-placed", version = "1.0.0" }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImplementedEventContract {

    /** Full contract reference: "<collection-id>/<contract-id>" */
    private String id;

    /** Version of the events artifact this implementation was written against. */
    private String version;

    public String getId()      { return id; }
    public void setId(String id) { this.id = id; }

    public String getVersion()           { return version; }
    public void setVersion(String version) { this.version = version; }

    @Override
    public String toString() {
        return "ImplementedEventContract{id='" + id + "', version='" + version + "'}";
    }
}
