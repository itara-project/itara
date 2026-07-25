package com.example.inventory;

import com.example.inventory.api.InventoryClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryService implements InventoryClient {

    private final Map<String, Integer> stock = new ConcurrentHashMap<>(Map.of(
            "widget", 10,
            "gadget", 5,
            "gizmo", 0
    ));

    @Override
    public int getStock(String itemId) {
        return stock.getOrDefault(itemId, 0);
    }

    @Override
    public synchronized boolean reserve(String itemId) {
        int current = stock.getOrDefault(itemId, 0);
        if (current <= 0) {
            return false;
        }
        stock.put(itemId, current - 1);
        return true;
    }
}
