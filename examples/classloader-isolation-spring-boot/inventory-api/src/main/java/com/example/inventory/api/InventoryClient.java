package com.example.inventory.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

@ComponentInterface(id = "inventory")
public interface InventoryClient {

    @ContractMethod(idempotent = true)
    int getStock(String itemId);

    @ContractMethod
    boolean reserve(String itemId);
}
