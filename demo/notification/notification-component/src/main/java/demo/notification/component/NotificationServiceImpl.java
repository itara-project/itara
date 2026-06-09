package demo.notification.component;

import demo.notification.api.NotificationService;

import java.util.logging.Logger;

/**
 * Dummy notification implementation.
 * Logs what it would do — no actual notifications are sent.
 */
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = Logger.getLogger(NotificationServiceImpl.class.getName());

    @Override
    public void notifyOrderReserved(String orderId, String productId, int quantity) {
        log.info("[Notification] Order reserved — orderId=" + orderId
                + ", productId=" + productId + ", quantity=" + quantity);
    }

    @Override
    public void notifyOrderFulfilled(String orderId) {
        log.info("[Notification] Order fulfilled — orderId=" + orderId);
    }

    @Override
    public void notifyOrderCancelled(String orderId) {
        log.info("[Notification] Order cancelled — orderId=" + orderId);
    }
}
