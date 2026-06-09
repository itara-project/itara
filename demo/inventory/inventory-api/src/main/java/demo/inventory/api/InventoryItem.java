package demo.inventory.api;

public class InventoryItem {

    private String productId;
    private String productName;
    private int    totalQuantity;
    private int    reservedQuantity;

    public InventoryItem() {}

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

    public void setProductId(String productId)           { this.productId        = productId; }
    public void setProductName(String productName)       { this.productName      = productName; }
    public void setTotalQuantity(int totalQuantity)      { this.totalQuantity    = totalQuantity; }
    public void setReservedQuantity(int reservedQuantity){ this.reservedQuantity = reservedQuantity; }

    @Override
    public String toString() {
        return String.format("[%s] %s — total: %d, reserved: %d, available: %d",
                productId, productName, totalQuantity, reservedQuantity, getAvailableQuantity());
    }
}
