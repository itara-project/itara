package demo.inventory.component;

import demo.inventory.api.InventoryService;
import demo.inventory.db.DatabaseManager;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

import java.util.logging.Logger;

/**
 * Activator for the inventory component.
 * Constructs the DatabaseManager and returns the implementation instance.
 * No dependencies on other components — inventory is self-contained.
 */
public class InventoryActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(InventoryActivator.class.getName());

    @Override
    public InventoryService activate(ItaraRegistry registry) {
        log.info("[InventoryActivator] Creating InventoryServiceImpl");
        try {
            DatabaseManager db = new DatabaseManager("inventory.db");
            return new InventoryServiceImpl(db);
        } catch (Exception e) {
            throw new RuntimeException("Failed to activate inventory component", e);
        }
    }
}
