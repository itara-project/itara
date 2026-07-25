package com.example.order;

import com.example.inventory.api.InventoryClient;
import io.itara.runtime.ItaraRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Bootstrap class used only when this component is activated by Itara.
 * Kept separate from OrderApplication so the plain "run via main()" story
 * stays untouched and identical to before.
 *
 * The InventoryClient bean here fetches the real, in-process
 * implementation directly from the registry — no HTTP involved. This
 * @Bean method runs during SpringApplication.run(), which executes
 * entirely inside the activator's TCCL-swapped scope, so the registry
 * lookup happens under the correct classloader naturally, with no special
 * threading needed.
 *
 * The exclude filter mirrors OrderApplication's — see there for why it's
 * needed regardless of which class is the active entry point.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = OrderApplication.class))
public class OrderItaraConfig {

    @Bean
    public InventoryClient inventoryClient() {
        return ItaraRegistry.instance().get("inventory", InventoryClient.class);
    }
}
