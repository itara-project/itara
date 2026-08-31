package demo.events.producer;

import demo.events.api.OrderCancelledContract;
import dev.itara.api.ItaraActivator;
import demo.events.api.OrderPlacedContract;
import demo.events.producer.api.OrderProducerService;
import dev.itara.runtime.ComponentLookup;
import payment.events.api.PaymentMadeContract;

public class OrderProducerActivator implements ItaraActivator {

    @Override
    public OrderProducerService activate() throws Exception {
        OrderPlacedContract orderPlacedContract = ComponentLookup.get("order-events/order-placed", OrderPlacedContract.class);
        OrderCancelledContract orderCancelledContract = ComponentLookup.get("order-events/order-cancelled", OrderCancelledContract.class);
        PaymentMadeContract paymentMadeContract = ComponentLookup.get("payment-events/payment-made", PaymentMadeContract.class);
        return new OrderProducerServiceImpl(orderPlacedContract, orderCancelledContract, paymentMadeContract);
    }
}
