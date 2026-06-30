package demo.inventory.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class DatabaseManager implements AutoCloseable {

    private static final Logger log = Logger.getLogger(DatabaseManager.class.getName());

    private final Connection connection;

    public DatabaseManager(String dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found on classpath", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
        }
        initSchema();
        log.info("[DatabaseManager] Ready at: " + dbPath);
    }

    private void initSchema() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS inventory (
                    product_id        TEXT    PRIMARY KEY,
                    product_name      TEXT    NOT NULL,
                    total_quantity    INTEGER NOT NULL CHECK(total_quantity    >= 0),
                    reserved_quantity INTEGER NOT NULL DEFAULT 0
                                                      CHECK(reserved_quantity >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS reservations (
                    reservation_id TEXT    PRIMARY KEY,
                    order_id       TEXT    NOT NULL UNIQUE,
                    product_id     TEXT    NOT NULL,
                    quantity       INTEGER NOT NULL,
                    status         TEXT    NOT NULL DEFAULT 'ACTIVE',
                    created_at     TEXT    NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES inventory(product_id)
                )
                """);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
