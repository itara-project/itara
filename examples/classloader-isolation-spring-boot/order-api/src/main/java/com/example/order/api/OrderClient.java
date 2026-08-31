package com.example.order.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

@ComponentInterface(id = "order")
public interface OrderClient {

    @ContractMethod
    String placeOrder(String itemId);
}
