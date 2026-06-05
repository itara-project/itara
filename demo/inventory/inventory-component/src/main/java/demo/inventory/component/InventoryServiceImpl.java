package demo.inventory.component;

import demo.inventory.api.InsufficientStockException;
import demo.inventory.api.InventoryItem;
import demo.inventory.api.InventoryService;
import demo.inventory.api.Reservation;
import demo.inventory.api.ReservationStatus;
import demo.inventory.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The inventory implementation.
 * Lives in inventory-component.jar.
 * Has no knowledge of how it is called — direct or HTTP.
 *
 * Pessimistic locking: ReentrantLock at the Java level + BEGIN IMMEDIATE at the SQLite level.
 */
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = Logger.getLogger(InventoryServiceImpl.class.getName());

    private final Connection    conn;
    private final ReentrantLock writeLock = new ReentrantLock();

    public InventoryServiceImpl(DatabaseManager db) throws SQLException {
        this.conn = db.getConnection();
    }

    @Override
    public InventoryItem addItem(String productId, String productName, int quantity) {
        String sql = """
            INSERT INTO inventory (product_id, product_name, total_quantity, reserved_quantity)
            VALUES (?, ?, ?, 0)
            ON CONFLICT(product_id) DO UPDATE
                SET total_quantity = total_quantity + excluded.total_quantity
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            ps.setString(2, productName);
            ps.setInt(3, quantity);
            ps.executeUpdate();
            InventoryItem item = fetchItem(productId);
            log.info("[Inventory] Added item: " + item);
            return item;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add item: " + productId, e);
        }
    }

    @Override
    public boolean checkAvailability(String productId, int quantity) {
        String sql = "SELECT (total_quantity - reserved_quantity) FROM inventory WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            ResultSet rs = ps.executeQuery();
            boolean available = rs.next() && rs.getInt(1) >= quantity;
            log.info("[Inventory] checkAvailability(" + productId + ", " + quantity + ") = " + available);
            return available;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check availability: " + productId, e);
        }
    }

    @Override
    public Reservation reserveOrder(String orderId, String productId, int quantity)
            throws InsufficientStockException {

        writeLock.lock();
        try {
            exec("BEGIN IMMEDIATE");
            try {
                int available = queryAvailable(productId);
                if (available < quantity) {
                    throw new InsufficientStockException(productId, quantity, available);
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE inventory SET reserved_quantity = reserved_quantity + ? WHERE product_id = ?")) {
                    ps.setInt(1, quantity);
                    ps.setString(2, productId);
                    ps.executeUpdate();
                }

                String        id  = UUID.randomUUID().toString();
                LocalDateTime now = LocalDateTime.now();
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO reservations (reservation_id, order_id, product_id, quantity, status, created_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                        """)) {
                    ps.setString(1, id);
                    ps.setString(2, orderId);
                    ps.setString(3, productId);
                    ps.setInt(4, quantity);
                    ps.setString(5, now.toString());
                    ps.executeUpdate();
                }

                exec("COMMIT");

                Reservation reservation = new Reservation(id, orderId, productId, quantity, ReservationStatus.ACTIVE, now);
                log.info("[Inventory] Reserved: " + reservation);
                return reservation;

            } catch (InsufficientStockException e) {
                exec("ROLLBACK");
                throw e;
            } catch (Exception e) {
                exec("ROLLBACK");
                throw new RuntimeException("Failed to reserve order: " + orderId, e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void releaseReservation(String orderId, boolean fulfilled) {
        writeLock.lock();
        try {
            exec("BEGIN IMMEDIATE");
            try {
                String resId, productId;
                int quantity;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT reservation_id, product_id, quantity
                        FROM   reservations
                        WHERE  order_id = ? AND status = 'ACTIVE'
                        """)) {
                    ps.setString(1, orderId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        throw new IllegalArgumentException("No active reservation for order: " + orderId);
                    }
                    resId     = rs.getString(1);
                    productId = rs.getString(2);
                    quantity  = rs.getInt(3);
                }

                if (fulfilled) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                            UPDATE inventory
                            SET total_quantity = total_quantity - ?, reserved_quantity = reserved_quantity - ?
                            WHERE product_id = ?
                            """)) {
                        ps.setInt(1, quantity);
                        ps.setInt(2, quantity);
                        ps.setString(3, productId);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE inventory SET reserved_quantity = reserved_quantity - ? WHERE product_id = ?")) {
                        ps.setInt(1, quantity);
                        ps.setString(2, productId);
                        ps.executeUpdate();
                    }
                }

                ReservationStatus status = fulfilled ? ReservationStatus.FULFILLED : ReservationStatus.CANCELLED;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reservations SET status = ? WHERE reservation_id = ?")) {
                    ps.setString(1, status.name());
                    ps.setString(2, resId);
                    ps.executeUpdate();
                }

                exec("COMMIT");
                log.info("[Inventory] Released reservation for order=" + orderId + " as " + status);

            } catch (Exception e) {
                exec("ROLLBACK");
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------

    private int queryAvailable(String productId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT (total_quantity - reserved_quantity) FROM inventory WHERE product_id = ?")) {
            ps.setString(1, productId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Product not found: " + productId);
            return rs.getInt(1);
        }
    }

    private InventoryItem fetchItem(String productId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT product_id, product_name, total_quantity, reserved_quantity FROM inventory WHERE product_id = ?")) {
            ps.setString(1, productId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Product not found: " + productId);
            return new InventoryItem(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4));
        }
    }

    private void exec(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("SQL error: " + sql, e);
        }
    }
}
