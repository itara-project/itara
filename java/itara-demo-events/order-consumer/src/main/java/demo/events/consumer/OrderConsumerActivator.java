package demo.events.consumer;

import demo.events.consumer.api.OrderConsumerService;
import io.itara.api.ItaraActivator;
import demo.events.api.OrderPlacedContract;
import io.itara.runtime.ItaraRegistry;

public class OrderConsumerActivator implements ItaraActivator<OrderConsumerService> {

    @Override
    public OrderConsumerService activate(ItaraRegistry registry) {
        return new OrderConsumerServiceImpl();
    }
}
