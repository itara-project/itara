package com.example.order;

import com.example.inventory.api.InventoryClient;
import org.springframework.web.client.RestClient;

/**
 * InventoryClient implementation used only when order-service runs
 * standalone (via OrderApplication.main()) — reaches inventory over real
 * HTTP, exactly as before. Never used when activated by Itara; see
 * OrderItaraConfig for that path, which fetches the real in-process
 * InventoryClient directly from the registry instead.
 */
public class HttpInventoryClient implements InventoryClient {

    private final RestClient restClient;

    public HttpInventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public int getStock(String itemId) {
        Integer stock = restClient.get()
                .uri("/inventory/{itemId}", itemId)
                .retrieve()
                .body(Integer.class);
        return stock != null ? stock : 0;
    }

    @Override
    public boolean reserve(String itemId) {
        Boolean reserved = restClient.post()
                .uri("/inventory/{itemId}/reserve", itemId)
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(reserved);
    }
}
