package demo.notification.component;

import demo.fulfilment.events.OrderCancelledContract;
import demo.fulfilment.events.OrderFulfilledContract;
import demo.notification.api.NotificationService;
import demo.order.events.OrderReservedContract;

import java.util.logging.Logger;

/**
 * Dummy notification implementation.
 * Logs what it would do — no actual notifications are sent.
 */
public class NotificationServiceImpl implements NotificationService, OrderReservedContract,
        OrderFulfilledContract, OrderCancelledContract {

    private static final Logger log = Logger.getLogger(NotificationServiceImpl.class.getName());

    @Override
    public void onOrderReserved(String orderId, String productId, int quantity) {
        log.info("[Notification] Order reserved — orderId=" + orderId
                + ", productId=" + productId + ", quantity=" + quantity);
    }

    @Override
    public void onOrderFulfilled(String orderId) {
        log.info("[Notification] Order fulfilled — orderId=" + orderId);
    }

    @Override
    public void onOrderCancelled(String orderId) {
        log.info("[Notification] Order cancelled — orderId=" + orderId);
    }
}
