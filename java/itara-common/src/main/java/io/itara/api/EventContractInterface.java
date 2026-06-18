package io.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an Itara event contract.
 *
 * Place this annotation on event contract interfaces in an events artifact jar.
 * The events artifact jar must have a corresponding .itara metadata file with
 * kind = "events" and an id declaring the collection name.
 *
 * The agent constructs the full contract reference at startup by combining
 * the collection id from the .itara file with this annotation's id:
 *   <collection-id>/<contract-id>
 * e.g. if the .itara file has id = "order-events" and this annotation
 * has id = "order-placed", the full reference is "order-events/order-placed".
 *
 * This full reference must match the 'contract' field on the virtual node
 * in the wiring config.
 *
 * Event contract methods MUST return void — event-driven communication is
 * fire-and-forget. See spec §13.2.2.
 *
 * Example:
 *   @EventContractInterface(id = "order-placed")
 *   public interface OrderPlacedContract {
 *       void onOrderPlaced(String orderId, String customerId, double total);
 *   }
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventContractInterface {
    String id();
}
