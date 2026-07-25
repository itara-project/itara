package com.example.order;

import com.example.inventory.api.InventoryClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.client.RestClient;

/**
 * Standalone entry point — run via main(), never coexists in the same
 * context as OrderItaraConfig. The exclude filter below prevents
 * component scanning from picking up OrderItaraConfig's conflicting
 * inventoryClient bean regardless of which class is passed to
 * SpringApplication.run() — both classes share this package, so without
 * the exclusion, whichever one runs would still scan and register the
 * other's @Bean methods too.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = OrderItaraConfig.class))
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

    @Bean
    public RestClient inventoryRestClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:8082")
                .build();
    }

    @Bean
    public InventoryClient inventoryClient(RestClient inventoryRestClient) {
        return new HttpInventoryClient(inventoryRestClient);
    }
}
