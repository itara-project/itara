package com.example.inventory;

import com.example.inventory.api.InventoryClient;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;
import org.springframework.context.ConfigurableApplicationContext;

public class InventoryActivator implements ItaraActivator {

    @Override
    public InventoryClient activate(ItaraRegistry registry) {
        // Reuses the existing @SpringBootApplication class as-is — no
        // separate config class needed, since the bean composition should
        // be identical whether run standalone (via main()) or activated
        // here. main() was only ever a thin wrapper around this same call.
        ConfigurableApplicationContext context =
                org.springframework.boot.SpringApplication.run(InventoryItaraConfig.class, new String[0]);

        return context.getBean(InventoryService.class);
    }
}
