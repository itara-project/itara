package demo.order.component;

import demo.fulfilment.api.FulfilmentService;
import demo.fulfilment.events.OrderCancelledContract;
import demo.fulfilment.events.OrderFulfilledContract;
import demo.inventory.api.InventoryService;
import demo.order.api.OrderService;
import demo.order.events.OrderReservedContract;
import demo.payment.api.PaymentService;
import dev.itara.api.ItaraActivator;
import dev.itara.runtime.ComponentLookup;

import java.util.logging.Logger;

public class OrderActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(OrderActivator.class.getName());

    @Override
    public OrderService activate() {
        log.info("[OrderActivator] Pulling dependencies from registry...");
        InventoryService    inventory    = ComponentLookup.get("inventory",    InventoryService.class);
        PaymentService      payment      = ComponentLookup.get("payment",      PaymentService.class);
        FulfilmentService   fulfilment   = ComponentLookup.get("fulfilment",   FulfilmentService.class);

        OrderCancelledContract orderCancelledContract = ComponentLookup.get("fulfilment-events/order-cancelled",
                OrderCancelledContract.class);
        OrderFulfilledContract orderFulfilledContract = ComponentLookup.get("fulfilment-events/order-fulfilled",
                OrderFulfilledContract.class);
        OrderReservedContract orderReservedContract = ComponentLookup.get("order-events/order-reserved",
                OrderReservedContract.class);

        log.info("[OrderActivator] Creating OrderServiceImpl");
        return new OrderServiceImpl(inventory, payment, fulfilment, orderCancelledContract,
                orderFulfilledContract, orderReservedContract);
    }
}
