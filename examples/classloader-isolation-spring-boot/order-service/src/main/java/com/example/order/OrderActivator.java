package com.example.order;

import com.example.order.api.OrderClient;
import io.itara.api.ItaraActivator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class OrderActivator implements ItaraActivator {

    @Override
    public OrderClient activate() {
        // The registry param is unused here deliberately — the actual
        // cross-component fetch (inventory) happens inside
        // OrderItaraConfig's @Bean method, as part of Spring's own
        // context refresh, not here in the activator body.
        ConfigurableApplicationContext context =
                SpringApplication.run(OrderItaraConfig.class, new String[0]);

        return context.getBean(OrderClient.class);
    }
}
