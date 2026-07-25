package com.example.order;

import com.example.inventory.api.InventoryClient;
import com.example.order.api.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderService implements OrderClient {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final InventoryClient inventoryClient;

    public OrderService(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Override
    public String placeOrder(String itemId) {
        boolean reserved = inventoryClient.reserve(itemId);

        log.info("placeOrder({}) -> inventory reserve result={}", itemId, reserved);

        return reserved
                ? "order placed for " + itemId
                : "order rejected for " + itemId + " (out of stock)";
    }
}
