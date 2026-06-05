package demo.inventory.api;

public class InventoryItem {

    private final String productId;
    private final String productName;
    private final int    totalQuantity;
    private final int    reservedQuantity;

    public InventoryItem(String productId, String productName, int totalQuantity, int reservedQuantity) {
        this.productId        = productId;
        this.productName      = productName;
        this.totalQuantity    = totalQuantity;
        this.reservedQuantity = reservedQuantity;
    }

    public String getProductId()         { return productId; }
    public String getProductName()       { return productName; }
    public int    getTotalQuantity()     { return totalQuantity; }
    public int    getReservedQuantity()  { return reservedQuantity; }
    public int    getAvailableQuantity() { return totalQuantity - reservedQuantity; }

    @Override
    public String toString() {
        return String.format("[%s] %s — total: %d, reserved: %d, available: %d",
                productId, productName, totalQuantity, reservedQuantity, getAvailableQuantity());
    }
}
