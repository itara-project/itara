package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Deliberately lazy — not instantiated at ApplicationContext refresh, only
 * on first access. Constructor does a real classloading-sensitive
 * operation so this bean actually exercises TCCL rather than just logging.
 */
@Lazy
@Component
public class InventoryDiagnosticBean {

    private static final Logger log = LoggerFactory.getLogger(InventoryDiagnosticBean.class);

    private final String loadedMarkerClassName;

    public InventoryDiagnosticBean() {
        Thread thread = Thread.currentThread();
        ClassLoader tccl = thread.getContextClassLoader();

        log.info("[LAZY-INIT] InventoryDiagnosticBean constructing on thread={} tccl={}",
                thread.getName(), tccl);

        try {
            Class<?> loaded = tccl.loadClass("com.example.inventory.InventoryService");
            this.loadedMarkerClassName = loaded.getName();
            log.info("[LAZY-INIT] loadClass succeeded, resolved={} loadedBy={}",
                    loaded.getName(), loaded.getClassLoader());
        } catch (ClassNotFoundException e) {
            log.error("[LAZY-INIT] loadClass FAILED under tccl={}", tccl, e);
            throw new IllegalStateException("Diagnostic loadClass failed", e);
        }
    }

    public String describe() {
        return "InventoryDiagnosticBean loaded=" + loadedMarkerClassName;
    }
}
