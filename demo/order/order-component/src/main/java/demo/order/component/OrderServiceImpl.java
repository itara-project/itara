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
            // Direct call (collocated topology) — original typed exception is preserved
            log.warning("[Order] Insufficient stock for order=" + orderId + ": " + e.getMessage());
            return false;
        } catch (ItaraRemoteException e) {
            // Remote call — Itara preserves the original exception class name across the wire,
            // but does not reconstruct the original type. We inspect the class name to identify it.
            // In the future, checked exception declared on the API might be reconstructed and thrown,
            // so the caller doesn't need to know about how Itara handles the errors. It is under discussion
            if (InsufficientStockException.class.getName().equals(e.getRemoteExceptionClass())) {
                log.warning("[Order] Insufficient stock (remote) for order=" + orderId + ": " + e.getMessage());
                return false;
            }
            throw e;
        }

        boolean paid = payment.processPayment(orderId, amountCents, currency);

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
