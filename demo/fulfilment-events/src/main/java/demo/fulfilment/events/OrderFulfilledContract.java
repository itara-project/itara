package demo.fulfilment.events;

import dev.itara.api.EventContractInterface;

@EventContractInterface(id = "order-fulfilled")
public interface OrderFulfilledContract {
    void onOrderFulfilled(String orderId);
}
