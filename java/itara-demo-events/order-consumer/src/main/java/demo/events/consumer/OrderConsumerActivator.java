package demo.events.consumer;

import demo.events.consumer.api.OrderConsumerService;
import dev.itara.api.ItaraActivator;

public class OrderConsumerActivator implements ItaraActivator {

    @Override
    public OrderConsumerService activate() {
        return new OrderConsumerServiceImpl();
    }
}
