package com.example.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Deliberately lazy — mirrors InventoryDiagnosticBean. Not instantiated at
 * ApplicationContext refresh, only on first access.
 */
@Lazy
@Component
public class OrderDiagnosticBean {

    private static final Logger log = LoggerFactory.getLogger(OrderDiagnosticBean.class);

    private final String loadedMarkerClassName;

    public OrderDiagnosticBean() {
        Thread thread = Thread.currentThread();
        ClassLoader tccl = thread.getContextClassLoader();

        log.info("[LAZY-INIT] OrderDiagnosticBean constructing on thread={} tccl={}",
                thread.getName(), tccl);

        try {
            Class<?> loaded = tccl.loadClass("com.example.order.OrderController");
            this.loadedMarkerClassName = loaded.getName();
            log.info("[LAZY-INIT] loadClass succeeded, resolved={} loadedBy={}",
                    loaded.getName(), loaded.getClassLoader());
        } catch (ClassNotFoundException e) {
            log.error("[LAZY-INIT] loadClass FAILED under tccl={}", tccl, e);
            throw new IllegalStateException("Diagnostic loadClass failed", e);
        }
    }

    public String describe() {
        return "OrderDiagnosticBean loaded=" + loadedMarkerClassName;
    }
}
