package demo.inventory.api;

import java.time.LocalDateTime;

public class Reservation {

    private String            reservationId;
    private String            orderId;
    private String            productId;
    private int               quantity;
    private ReservationStatus status;
    private LocalDateTime     createdAt;

    public Reservation() {}

    public Reservation(String reservationId, String orderId, String productId,
                       int quantity, ReservationStatus status, LocalDateTime createdAt) {
        this.reservationId = reservationId;
        this.orderId       = orderId;
        this.productId     = productId;
        this.quantity      = quantity;
        this.status        = status;
        this.createdAt     = createdAt;
    }

    public String            getReservationId() { return reservationId; }
    public String            getOrderId()       { return orderId; }
    public String            getProductId()     { return productId; }
    public int               getQuantity()      { return quantity; }
    public ReservationStatus getStatus()        { return status; }
    public LocalDateTime     getCreatedAt()     { return createdAt; }

    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public void setOrderId(String orderId)             { this.orderId       = orderId; }
    public void setProductId(String productId)         { this.productId     = productId; }
    public void setQuantity(int quantity)              { this.quantity      = quantity; }
    public void setStatus(ReservationStatus status)    { this.status        = status; }
    public void setCreatedAt(LocalDateTime createdAt)  { this.createdAt     = createdAt; }

    @Override
    public String toString() {
        return String.format("Reservation{id=%s, order=%s, product=%s, qty=%d, status=%s}",
            reservationId, orderId, productId, quantity, status);
    }
}
