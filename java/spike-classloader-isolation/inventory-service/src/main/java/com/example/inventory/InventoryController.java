package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
        log.info("[SPIKE] GET /inventory/{} -> {}", itemId, stock);
        return stock;
    }

    @PostMapping("/{itemId}/reserve")
    public boolean reserve(@PathVariable String itemId) {
        boolean reserved = inventoryService.reserve(itemId);
        log.info("[SPIKE] POST /inventory/{}/reserve -> {}", itemId, reserved);
        return reserved;
    }

    @GetMapping("/lazy-check")
    public String lazyCheck() {
        // First touch of diagnosticBean happens here, deliberately deferred
        // until this endpoint is hit rather than at context refresh.
        String result = diagnosticBean.describe();
        log.info("[SPIKE] GET /inventory/lazy-check -> {}", result);
        return result;
    }

    @GetMapping("/autoconfig-check")
    public String autoConfigCheck() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        List<String> autoConfigs = new ArrayList<>();
        ImportCandidates.load(AutoConfiguration.class, cl).forEach(autoConfigs::add);

        List<String> failureAnalyzers =
                SpringFactoriesLoader.loadFactoryNames(
                        org.springframework.boot.diagnostics.FailureAnalyzer.class, cl);

        log.info("[SPIKE][AUTOCONFIG] component=inventory tccl={} autoConfigCount={} autoConfigs={}",
                cl, autoConfigs.size(), autoConfigs);
        log.info("[SPIKE][AUTOCONFIG] component=inventory failureAnalyzerCount={} failureAnalyzers={}",
                failureAnalyzers.size(), failureAnalyzers);

        boolean sawItaraClass = autoConfigs.stream().anyMatch(name -> name.startsWith("io.itara."))
                || failureAnalyzers.stream().anyMatch(name -> name.startsWith("io.itara."));
        boolean sawOrderClass = autoConfigs.stream().anyMatch(name -> name.startsWith("com.example.order"))
                || failureAnalyzers.stream().anyMatch(name -> name.startsWith("com.example.order"));

        return "autoConfigs=" + autoConfigs.size()
                + " failureAnalyzers=" + failureAnalyzers.size()
                + " sawItaraClass=" + sawItaraClass
                + " sawOrderClass=" + sawOrderClass;
    }
}
