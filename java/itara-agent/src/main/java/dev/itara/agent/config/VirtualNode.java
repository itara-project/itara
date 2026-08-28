package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A virtual node declared in the wiring configuration.
 *
 * <p>A virtual node represents a communication channel — a named point through
 * which producers and consumers are decoupled. It has no component
 * implementation, no activator, and no agent-managed lifecycle.
 *
 * <p>Fields:
 * <ul>
 * <li>contract — the event contract this channel carries, in the form
 * "events-artifact-id/contract-id" (e.g. "order-events/order-placed")</li>
 * <li>address — broker-specific channel address (Kafka topic name, etc.)</li>
 * </ul>
 *
 * <p>Example YAML:
 * <pre>{@code
 * nodes:
 *   - id: "orderPlacedChannel"
 *     kind: virtual
 *     contract: "order-events/order-placed"
 *     address: "demo.events.order-placed"
 * }</pre>
 *
 * <p>See spec §13.2.1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirtualNode extends Node {

    private String contract;
    private String address;

    /** Constructs a virtual node, defaulting {@code kind} to {@link NodeKind#VIRTUAL}. */
    public VirtualNode() {
        setKind(NodeKind.VIRTUAL);
    }

    /** @return the event contract this channel carries */
    public String getContract()              { return contract; }
    /** @param contract the event contract this channel carries */
    public void setContract(String contract) { this.contract = contract; }

    /** @return the broker-specific channel address */
    public String getAddress()             { return address; }
    /** @param address the broker-specific channel address */
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
