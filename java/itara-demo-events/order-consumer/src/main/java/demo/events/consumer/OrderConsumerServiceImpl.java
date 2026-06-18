package demo.events.consumer;

import demo.events.api.OrderPlacedContract;
import demo.events.consumer.api.OrderConsumerService;

import java.util.logging.Logger;

/**
 * Receives order-placed events from Kafka via the Itara agent
 * and logs them. No knowledge of Kafka or transport details.
 */
public class OrderConsumerServiceImpl implements OrderPlacedContract, OrderConsumerService {

    private static final Logger log = Logger.getLogger(OrderConsumerServiceImpl.class.getName());

    @Override
    public void onOrderPlaced(String orderId, String customerId, double amount) {
        log.info("[OrderConsumer] Received order-placed event:"
                + " orderId=" + orderId
                + " customerId=" + customerId
                + " amount=" + amount);
    }
}
