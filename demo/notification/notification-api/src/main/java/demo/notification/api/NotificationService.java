package demo.notification.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the notification component.
 * Lives in notification-api.jar.
 */
@ComponentInterface(id = "notification")
public interface NotificationService {

    @ContractMethod
    void notifyOrderReserved(String orderId, String productId, int quantity);

    @ContractMethod
    void notifyOrderFulfilled(String orderId);

    @ContractMethod
    void notifyOrderCancelled(String orderId);
}
