package demo.order.events;

import io.itara.api.EventContractInterface;

@EventContractInterface(id = "order-reserved")
public interface OrderReservedContract {
    void onOrderReserved(String orderId, String productId, int quantity);
}
