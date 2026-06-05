package demo.inventory.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the inventory component.
 * Lives in inventory-api.jar.
 * Any component that needs to interact with inventory depends only on this jar.
 */
@ComponentInterface(id = "inventory")
public interface InventoryService {

    /**
     * Adds a new product to the inventory, or tops up the stock of an existing one.
     */
    @ContractMethod
    InventoryItem addItem(String productId, String productName, int quantity);

    /**
     * Returns true if at least {@code quantity} units are currently available.
     * Advisory — no stock is held. Use reserveOrder to actually claim stock.
     */
    @ContractMethod(idempotent = true)
    boolean checkAvailability(String productId, int quantity);

    /**
     * Reserves {@code quantity} units for the given order, atomically.
     *
     * @throws InsufficientStockException if available stock is less than requested — checked, caller must handle
     * @throws IllegalArgumentException   if the product does not exist — runtime
     */
    @ContractMethod
    Reservation reserveOrder(String orderId, String productId, int quantity)
            throws InsufficientStockException;

    /**
     * Closes the reservation for the given order.
     *
     * fulfilled=true  → order executed; stock is permanently deducted.
     * fulfilled=false → order cancelled; stock is returned to the available pool.
     */
    @ContractMethod
    void releaseReservation(String orderId, boolean fulfilled);
}
