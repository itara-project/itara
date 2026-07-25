package com.example.inventory;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Bootstrap class used only when this component is activated by Itara.
 * Kept separate from InventoryApplication so the plain "run via main()"
 * story stays untouched and identical to before — this class exists
 * purely so the activator has something to point SpringApplication.run()
 * at that isn't tied to a main() method.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = InventoryApplication.class))
public class InventoryItaraConfig {
}
