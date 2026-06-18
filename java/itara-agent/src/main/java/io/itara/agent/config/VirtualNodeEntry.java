package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A virtual node declared in the wiring configuration.
 *
 * A virtual node represents a communication channel — a named point through
 * which producers and consumers are decoupled. It has no component
 * implementation, no activator, and no agent-managed lifecycle.
 *
 * Fields:
 *   id       — stable identifier, unique within the wiring config
 *   contract — the event contract this channel carries, in the form
 *              "events-artifact-id/contract-id" (e.g. "order-events/order-created")
 *   address  — broker-specific channel address (Kafka topic name, queue name, etc.)
 *
 * Virtual nodes MUST NOT participate in deployment group computation and
 * MUST NOT be passed to ActivatorScanner — they have no component to activate.
 *
 * See spec §13.2.1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirtualNodeEntry {

    private String id;
    private String contract;
    private String address;

    public String getId()       { return id; }
    public void setId(String id) { this.id = id; }

    public String getContract()            { return contract; }
    public void setContract(String contract) { this.contract = contract; }

    public String getAddress()             { return address; }
    public void setAddress(String address) { this.address = address; }

    public void validate() {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException( "[Itara] Virtual node entry is missing required field 'id'.");
        }
        if (contract == null || contract.isBlank()) {
            throw new ConfigurationException( "[Itara] Virtual node '" + id + "' is missing required field 'contract'.");
        }
        if (address == null || address.isBlank()) {
            throw new ConfigurationException( "[Itara] Virtual node '" + id + "' is missing required field 'address'.");
        }
    }

    @Override
    public String toString() {
        return "VirtualNodeEntry{id='" + id + "', contract='" + contract + "', address='" + address + "'}";
    }
}
