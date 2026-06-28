package demo.events.producer;

import demo.events.api.OrderCancelledContract;
import io.itara.api.ItaraActivator;
import demo.events.api.OrderPlacedContract;
import demo.events.producer.api.OrderProducerService;
import io.itara.runtime.ItaraRegistry;
import payment.events.api.PaymentMadeContract;

public class OrderProducerActivator implements ItaraActivator {

    @Override
    public OrderProducerService activate(ItaraRegistry registry) throws Exception {
        OrderPlacedContract orderPlacedContract = registry.get("order-events/order-placed", OrderPlacedContract.class);
        OrderCancelledContract orderCancelledContract = registry.get("order-events/order-cancelled", OrderCancelledContract.class);
        PaymentMadeContract paymentMadeContract = registry.get("payment-events/payment-made", PaymentMadeContract.class);
        return new OrderProducerServiceImpl(orderPlacedContract, orderCancelledContract, paymentMadeContract);
    }
}
