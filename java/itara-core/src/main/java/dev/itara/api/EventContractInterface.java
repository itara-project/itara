package dev.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an Itara event contract.
 *
 * <p>Place this on event contract interfaces in an events artifact jar.
 * Discovered by the agent via {@code META-INF/itara/event-contract} in
 * that jar, which must have a corresponding {@code .itara} metadata file
 * with {@code kind = "events"}.
 *
 * <p>The agent combines that metadata file's collection id with this
 * annotation's id to form the full contract reference,
 * {@code <collection-id>/<contract-id>} — e.g. collection id
 * {@code "order-events"} and this annotation's id {@code "order-placed"}
 * produce {@code "order-events/order-placed"}. This full reference must
 * match the {@code contract} field on the virtual node in the wiring
 * config.
 *
 * <p>Event contract methods MUST return {@code void} — event-driven
 * communication is fire-and-forget (spec §13.2.2).
 *
 * <p>Example:
 * <pre>{@code
 * @EventContractInterface(id = "order-placed")
 * public interface OrderPlacedContract {
 *     void onOrderPlaced(String orderId, String customerId, double total);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventContractInterface {

    /**
     * This contract's id, unique within its events artifact. Combined
     * with the artifact's collection id to form the full contract
     * reference used in the wiring config.
     */
    String id();
}
