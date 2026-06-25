package demo.order.component;

import demo.fulfilment.api.FulfilmentService;
import demo.inventory.api.InsufficientStockException;
import demo.inventory.api.InventoryService;
import demo.notification.api.NotificationService;
import demo.order.api.OrderService;
import demo.payment.api.PaymentService;
import io.itara.exceptions.ItaraRemoteException;

import java.util.logging.Logger;

public class OrderServiceImpl implements OrderService {

    private static final Logger log = Logger.getLogger(OrderServiceImpl.class.getName());

    private final InventoryService    inventory;
    private final PaymentService      payment;
    private final FulfilmentService   fulfilment;
    private final NotificationService notification;

    public OrderServiceImpl(
            InventoryService    inventory,
            PaymentService      payment,
            FulfilmentService   fulfilment,
            NotificationService notification) {
        this.inventory    = inventory;
        this.payment      = payment;
        this.fulfilment   = fulfilment;
        this.notification = notification;
    }

    @Override
    public boolean placeOrder(String orderId, String productId, int quantity, long amountCents, String currency) {
        log.info("[Order] Placing order=" + orderId + " product=" + productId + " qty=" + quantity);

        try {
            inventory.reserveOrder(orderId, productId, quantity);
        } catch (InsufficientStockException e) {
            // In remote calls, checked exceptions can get reconstructed if opted in
            // In direct calls, exceptions simply surface as expected
            log.warning("[Order] Insufficient stock for order=" + orderId + ": " + e.getMessage());
            return false;
        }

        boolean paid = payment.process_payment(orderId, amountCents, currency);

        if (paid) {
            inventory.releaseReservation(orderId, true);
            fulfilment.processOrder(orderId, productId, quantity);
            notification.notifyOrderFulfilled(orderId);
            log.info("[Order] Order placed successfully — orderId=" + orderId);
            return true;
        } else {
            inventory.releaseReservation(orderId, false);
            notification.notifyOrderCancelled(orderId);
            log.warning("[Order] Payment failed, order cancelled — orderId=" + orderId);
            return false;
        }
    }

    @Override
    public void cancelOrder(String orderId) {
        log.info("[Order] Cancelling order=" + orderId);
        inventory.releaseReservation(orderId, false);
        fulfilment.cancelOrder(orderId);
        notification.notifyOrderCancelled(orderId);
        log.info("[Order] Order cancelled — orderId=" + orderId);
    }
}
