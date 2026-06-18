package demo.events.api;

import io.itara.api.EventContractInterface;

/**
 * Event contract for the order-placed event.
 *
 * Producer: order-producer fires this when a REST request arrives.
 * Consumer: order-consumer receives this and logs it.
 *
 * Methods must return void — fire-and-forget per spec §13.3.
 */
@EventContractInterface(id = "order-placed")
public interface OrderPlacedContract {
    void onOrderPlaced(String orderId, String customerId, double amount);
}
