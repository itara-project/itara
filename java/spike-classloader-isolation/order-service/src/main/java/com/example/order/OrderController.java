package com.example.order;

import com.example.order.api.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderClient orderClient;
    private final OrderDiagnosticBean diagnosticBean;

    public OrderController(OrderClient orderClient,
                           @Lazy OrderDiagnosticBean diagnosticBean) {
        this.orderClient = orderClient;
        this.diagnosticBean = diagnosticBean;
    }

    @PostMapping("/{itemId}")
    public String placeOrder(@PathVariable String itemId) {
        String result = orderClient.placeOrder(itemId);
        log.info("[SPIKE] POST /order/{} -> {}", itemId, result);
        return result;
    }

    @GetMapping("/lazy-check")
    public String lazyCheck() {
        String result = diagnosticBean.describe();
        log.info("[SPIKE] GET /order/lazy-check -> {}", result);
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

        log.info("[SPIKE][AUTOCONFIG] component=order tccl={} autoConfigCount={} autoConfigs={}",
                cl, autoConfigs.size(), autoConfigs);
        log.info("[SPIKE][AUTOCONFIG] component=order failureAnalyzerCount={} failureAnalyzers={}",
                failureAnalyzers.size(), failureAnalyzers);

        boolean sawItaraClass = autoConfigs.stream().anyMatch(name -> name.startsWith("io.itara."))
                || failureAnalyzers.stream().anyMatch(name -> name.startsWith("io.itara."));
        boolean sawInventoryClass = autoConfigs.stream().anyMatch(name -> name.startsWith("com.example.inventory"))
                || failureAnalyzers.stream().anyMatch(name -> name.startsWith("com.example.inventory"));

        return "autoConfigs=" + autoConfigs.size()
                + " failureAnalyzers=" + failureAnalyzers.size()
                + " sawItaraClass=" + sawItaraClass
                + " sawOrderClass=" + sawInventoryClass;
    }
}
