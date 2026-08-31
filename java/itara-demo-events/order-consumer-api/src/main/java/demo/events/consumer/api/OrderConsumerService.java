package demo.events.consumer.api;

import dev.itara.api.ComponentInterface;

@ComponentInterface(id = "order-consumer")
public interface OrderConsumerService {
    // Entry point for the order consumer component.
    // Event handling is declared via @EventContractInterface on the
    // event contract interfaces this component implements.
}
