package demo.order.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "order")
public interface OrderService {

    /**
     * Places an order — reserves stock, takes payment, triggers fulfilment.
     * Returns true if the order was successfully placed, false if payment failed.
     */
    @ContractMethod
    boolean placeOrder(String orderId, String productId, int quantity, long amountCents, String currency);

    /**
     * Cancels an active order — releases the stock reservation and notifies.
     */
    @ContractMethod
    void cancelOrder(String orderId);
}
