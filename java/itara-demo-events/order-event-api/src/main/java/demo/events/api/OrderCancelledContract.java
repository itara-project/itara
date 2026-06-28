package demo.events.api;

import io.itara.api.EventContractInterface;

@EventContractInterface(id = "order-cancelled")
public interface OrderCancelledContract {
    void onOrderCancelled(String orderId, String customerId);
}
