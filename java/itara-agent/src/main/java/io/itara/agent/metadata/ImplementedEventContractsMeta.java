package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The [implemented-event-contracts] section of a component's .itara
 * metadata file.
 *
 * Present only on component artifacts that consume event contracts.
 * Absent on api, transport, serializer, observer, and events artifacts.
 *
 * Example:
 *   [implemented-event-contracts]
 *   contracts = [
 *     { id = "order-events/order-placed",    version = "1.0.0" },
 *     { id = "order-events/order-cancelled", version = "1.0.0" }
 *   ]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImplementedEventContractsMeta {

    private List<ImplementedEventContract> contracts = new ArrayList<>();

    public List<ImplementedEventContract> getContracts() { return contracts; }
    public void setContracts(List<ImplementedEventContract> contracts) {
        this.contracts = contracts != null ? contracts : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ImplementedEventContractsMeta{contracts=" + contracts + "}";
    }

    public static ImplementedEventContractsMeta ofEmpty() {
        return new ImplementedEventContractsMeta();
    }
}
