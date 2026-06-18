package demo.events.producer;

import io.itara.api.ItaraActivator;
import demo.events.api.OrderPlacedContract;
import demo.events.producer.api.OrderProducerService;
import io.itara.runtime.ItaraRegistry;

public class OrderProducerActivator implements ItaraActivator<OrderProducerService> {

    @Override
    public OrderProducerService activate(ItaraRegistry registry) throws Exception {
        OrderPlacedContract contract = registry.get("order-events/order-placed", OrderPlacedContract.class);
        return new OrderProducerServiceImpl(contract);
    }
}
