package demo.fulfilment.events;

import io.itara.api.EventContractInterface;

@EventContractInterface(id = "order-fulfilled")
public interface OrderFulfilledContract {
    void onOrderFulfilled(String orderId);
}
