package demo.events.api;

import dev.itara.api.EventContractInterface;

@EventContractInterface(id = "order-cancelled")
public interface OrderCancelledContract {
    void onOrderCancelled(String orderId, String customerId);
}
