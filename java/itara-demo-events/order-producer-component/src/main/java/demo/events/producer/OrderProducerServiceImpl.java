package demo.events.producer;

import demo.events.api.OrderCancelledContract;
import demo.events.api.OrderPlacedContract;
import demo.events.producer.api.OrderProducerService;
import payment.events.api.PaymentMadeContract;

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
    private final OrderCancelledContract orderCancelledContract;
    private final PaymentMadeContract paymentMadeContract;

    public OrderProducerServiceImpl(OrderPlacedContract orderPlacedContract,
                                    OrderCancelledContract orderCancelledContract,
                                    PaymentMadeContract paymentMadeContract) {
        this.orderPlacedContract = orderPlacedContract;
        this.orderCancelledContract = orderCancelledContract;
        this.paymentMadeContract = paymentMadeContract;
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

    @Override
    public String cancelOrder(String orderId, String customerId) {
        log.info("[OrderProducer] Cancelling order " + orderId
                + " for customer " + customerId);

        orderCancelledContract.onOrderCancelled(orderId, customerId);
        return orderId;
    }

    @Override
    public String makePayment(String orderId, String customerId, double amount) {
        log.info("[OrderProducer] Making payment for order " + orderId
                + " for customer " + customerId
                + " amount " + amount);

        paymentMadeContract.onPaymentMade(orderId, customerId, amount);
        return orderId;
    }
}
