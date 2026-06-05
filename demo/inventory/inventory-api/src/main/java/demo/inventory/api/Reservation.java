package demo.inventory.api;

import java.time.LocalDateTime;

public class Reservation {

    private final String            reservationId;
    private final String            orderId;
    private final String            productId;
    private final int               quantity;
    private final ReservationStatus status;
    private final LocalDateTime     createdAt;

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

    @Override
    public String toString() {
        return String.format("Reservation{id=%s, order=%s, product=%s, qty=%d, status=%s}",
            reservationId, orderId, productId, quantity, status);
    }
}
