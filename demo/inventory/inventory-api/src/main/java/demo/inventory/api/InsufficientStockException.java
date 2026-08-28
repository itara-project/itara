package demo.inventory.api;

import dev.itara.exceptions.ItaraReconstructibleException;

/**
 * Thrown when a reservation cannot be satisfied due to insufficient stock.
 * Checked — callers are expected to handle the out-of-stock case explicitly.
 */
public class InsufficientStockException extends Exception implements ItaraReconstructibleException {

    private final String productId;
    private final int    requested;
    private final int    available;

    public InsufficientStockException(String productId, int requested, int available) {
        super(String.format("Insufficient stock for '%s': requested %d, available %d",
            productId, requested, available));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public InsufficientStockException(String message) {
        super(message);
        this.productId = "";
        this.requested = -1;
        this.available = -1;
    }

    public String getProductId() { return productId; }
    public int    getRequested() { return requested; }
    public int    getAvailable() { return available; }
}
