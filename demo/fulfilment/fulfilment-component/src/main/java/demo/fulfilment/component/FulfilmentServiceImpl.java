package demo.fulfilment.component;

import demo.fulfilment.api.FulfilmentService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.logging.Logger;

public class FulfilmentServiceImpl implements FulfilmentService {

    private static final Logger log = Logger.getLogger(FulfilmentServiceImpl.class.getName());
    private static final Path LOG_FILE = Path.of("fulfilment-log.txt");

    @Override
    public void processOrder(String orderId, String productId, int quantity) {
        String entry = LocalDateTime.now() + "  PROCESSED  orderId=" + orderId
                + "  productId=" + productId + "  quantity=" + quantity + System.lineSeparator();
        append(entry);
        log.info("[Fulfilment] Order processed — orderId=" + orderId
                + ", productId=" + productId + ", quantity=" + quantity);
    }

    @Override
    public void cancelOrder(String orderId) {
        String entry = LocalDateTime.now() + "  CANCELLED  orderId=" + orderId + System.lineSeparator();
        append(entry);
        log.info("[Fulfilment] Order cancelled — orderId=" + orderId);
    }

    private void append(String entry) {
        try {
            Files.writeString(LOG_FILE, entry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warning("[Fulfilment] Could not write to " + LOG_FILE + ": " + e.getMessage());
        }
    }
}
