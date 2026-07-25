package com.example.order;

import com.example.order.api.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        log.info("POST /order/{} -> {}", itemId, result);
        return result;
    }

    @GetMapping("/lazy-check")
    public String lazyCheck() {
        String result = diagnosticBean.describe();
        log.info("GET /order/lazy-check -> {}", result);
        return result;
    }
}
