package demo.events.producer.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

@ComponentInterface(id = "order-producer")
public interface OrderProducerService {
    @ContractMethod
    String placeOrder(String customerId, double amount);

    @ContractMethod
    String cancelOrder(String orderId, String customerId);

    @ContractMethod
    String makePayment(String orderId, String customerId, double amount);
}
