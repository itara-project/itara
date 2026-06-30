package demo.order.component;

import demo.fulfilment.api.FulfilmentService;
import demo.fulfilment.events.OrderCancelledContract;
import demo.fulfilment.events.OrderFulfilledContract;
import demo.inventory.api.InventoryService;
import demo.order.api.OrderService;
import demo.order.events.OrderReservedContract;
import demo.payment.api.PaymentService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

import java.util.logging.Logger;

public class OrderActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(OrderActivator.class.getName());

    @Override
    public OrderService activate(ItaraRegistry registry) {
        log.info("[OrderActivator] Pulling dependencies from registry...");
        InventoryService    inventory    = registry.get("inventory",    InventoryService.class);
        PaymentService      payment      = registry.get("payment",      PaymentService.class);
        FulfilmentService   fulfilment   = registry.get("fulfilment",   FulfilmentService.class);

        OrderCancelledContract orderCancelledContract = registry.get("fulfilment-events/order-cancelled",
                OrderCancelledContract.class);
        OrderFulfilledContract orderFulfilledContract = registry.get("fulfilment-events/order-fulfilled",
                OrderFulfilledContract.class);
        OrderReservedContract orderReservedContract = registry.get("order-events/order-reserved",
                OrderReservedContract.class);

        log.info("[OrderActivator] Creating OrderServiceImpl");
        return new OrderServiceImpl(inventory, payment, fulfilment, orderCancelledContract,
                orderFulfilledContract, orderReservedContract);
    }
}
