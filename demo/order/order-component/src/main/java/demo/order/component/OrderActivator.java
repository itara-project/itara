package demo.order.component;

import demo.fulfilment.api.FulfilmentService;
import demo.inventory.api.InventoryService;
import demo.notification.api.NotificationService;
import demo.order.api.OrderService;
import demo.payment.api.PaymentService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

import java.util.logging.Logger;

public class OrderActivator implements ItaraActivator<OrderService> {

    private static final Logger log = Logger.getLogger(OrderActivator.class.getName());

    @Override
    public OrderService activate(ItaraRegistry registry) {
        log.info("[OrderActivator] Pulling dependencies from registry...");
        InventoryService    inventory    = registry.get("inventory",    InventoryService.class);
        PaymentService      payment      = registry.get("payment",      PaymentService.class);
        FulfilmentService   fulfilment   = registry.get("fulfilment",   FulfilmentService.class);
        NotificationService notification = registry.get("notification", NotificationService.class);
        log.info("[OrderActivator] Creating OrderServiceImpl");
        return new OrderServiceImpl(inventory, payment, fulfilment, notification);
    }
}
