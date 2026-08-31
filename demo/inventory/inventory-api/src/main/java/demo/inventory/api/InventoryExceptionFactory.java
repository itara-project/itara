package demo.inventory.api;

import dev.itara.exceptions.ItaraReconstructibleException;
import dev.itara.exceptions.ItaraReconstructibleExceptionFactory;

import java.util.Optional;

public class InventoryExceptionFactory implements ItaraReconstructibleExceptionFactory {
    @Override
    public String contractId() {
        return "inventory";
    }

    @Override
    public Optional<ItaraReconstructibleException> reconstruct(String errorTypeId, String message) {
        if (errorTypeId.equals(InsufficientStockException.class.getName())) {
            return Optional.of(new InsufficientStockException(message));
        }
        return Optional.empty();
    }
}
