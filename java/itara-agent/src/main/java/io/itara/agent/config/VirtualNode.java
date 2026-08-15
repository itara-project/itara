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
 *   contract — the event contract this channel carries, in the form
 *              "events-artifact-id/contract-id"
 *              (e.g. "order-events/order-placed")
 *   address  — broker-specific channel address (Kafka topic name, etc.)
 *
 * Example YAML:
 *   nodes:
 *     - id: "orderPlacedChannel"
 *       kind: virtual
 *       contract: "order-events/order-placed"
 *       address: "demo.events.order-placed"
 *
 * See spec §13.2.1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirtualNode extends Node {

    private String contract;
    private String address;

    public VirtualNode() {
        setKind(NodeKind.VIRTUAL);
    }

    public String getContract()              { return contract; }
    public void setContract(String contract) { this.contract = contract; }

    public String getAddress()             { return address; }
    public void setAddress(String address) { this.address = address; }

    /**
     * Returns the full contract reference — used as the registry key
     * for producer proxies and the alias target for consumer dispatchers.
     * e.g. "order-events/order-placed"
     */
    @Override
    public String contractIdentifier() {
        return contract;
    }

    @Override
    public void validate() {
        validateId();
        if (contract == null || contract.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Virtual node '" + getId()
                            + "' is missing required field 'contract'.");
        }
        if (address == null || address.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Virtual node '" + getId()
                            + "' is missing required field 'address'.");
        }
    }

    @Override
    public String toString() {
        return "VirtualNode{id='" + getId() + "', contract='" + contract
                + "', address='" + address + "'}";
    }
}
