package demo.events.producer.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "order-producer")
public interface OrderProducerService {
    @ContractMethod
    String placeOrder(String customerId, double amount);
}
