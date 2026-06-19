package demo.events.producer;

import demo.events.api.OrderPlacedContract;
import demo.events.producer.api.OrderProducerService;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Accepts a REST call, generates an order id, and fires the
 * order-placed event through the Itara-managed Kafka proxy.
 * The producer has no knowledge of Kafka — it just calls the contract.
 */
public class OrderProducerServiceImpl implements OrderProducerService {

    private static final Logger log = Logger.getLogger(OrderProducerServiceImpl.class.getName());

    private final OrderPlacedContract orderPlacedContract;

    public OrderProducerServiceImpl(OrderPlacedContract orderPlacedContract) {
        this.orderPlacedContract = orderPlacedContract;
    }

    @Override
    public String placeOrder(String customerId, double amount) {
        String orderId = UUID.randomUUID().toString();
        log.info("[OrderProducer] Placing order " + orderId
                + " for customer " + customerId
                + " amount " + amount);

        orderPlacedContract.onOrderPlaced(orderId, customerId, amount);

        log.info("[OrderProducer] Event fired for order " + orderId);
        return orderId;
    }
}
