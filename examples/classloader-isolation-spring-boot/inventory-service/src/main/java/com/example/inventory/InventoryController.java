package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;
    private final InventoryDiagnosticBean diagnosticBean;

    public InventoryController(InventoryService inventoryService,
                               @Lazy InventoryDiagnosticBean diagnosticBean) {
        this.inventoryService = inventoryService;
        this.diagnosticBean = diagnosticBean;
    }

    @GetMapping("/{itemId}")
    public int getStock(@PathVariable String itemId) {
        int stock = inventoryService.getStock(itemId);
        log.info("GET /inventory/{} -> {}", itemId, stock);
        return stock;
    }

    @PostMapping("/{itemId}/reserve")
    public boolean reserve(@PathVariable String itemId) {
        boolean reserved = inventoryService.reserve(itemId);
        log.info("POST /inventory/{}/reserve -> {}", itemId, reserved);
        return reserved;
    }

    @GetMapping("/lazy-check")
    public String lazyCheck() {
        // First touch of diagnosticBean happens here, deliberately deferred
        // until this endpoint is hit rather than at context refresh.
        String result = diagnosticBean.describe();
        log.info("GET /inventory/lazy-check -> {}", result);
        return result;
    }
}
