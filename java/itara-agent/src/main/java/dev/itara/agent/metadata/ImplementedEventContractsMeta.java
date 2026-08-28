package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The [implemented-event-contracts] section of a component's .itara
 * metadata file.
 *
 * <p>Present only on component artifacts that consume event contracts.
 * Absent on api, transport, serializer, observer, and events artifacts.
 *
 * <p>Example:
 * <pre>{@code
 * [implemented-event-contracts]
 * contracts = [
 *   { id = "order-events/order-placed",    version = "1.0.0" },
 *   { id = "order-events/order-cancelled", version = "1.0.0" }
 * ]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImplementedEventContractsMeta {

    private List<ImplementedEventContract> contracts = new ArrayList<>();

    /** @return the event contracts this component implements; never null */
    public List<ImplementedEventContract> getContracts() { return contracts; }
    /** @param contracts the event contracts this component implements; null is treated as empty */
    public void setContracts(List<ImplementedEventContract> contracts) {
        this.contracts = contracts != null ? contracts : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ImplementedEventContractsMeta{contracts=" + contracts + "}";
    }

    /** @return an instance with no declared event contracts, for when the section is absent */
    public static ImplementedEventContractsMeta ofEmpty() {
        return new ImplementedEventContractsMeta();
    }
}
