package com.example.inventory.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "inventory")
public interface InventoryClient {

    @ContractMethod(idempotent = true)
    int getStock(String itemId);

    @ContractMethod
    boolean reserve(String itemId);
}
