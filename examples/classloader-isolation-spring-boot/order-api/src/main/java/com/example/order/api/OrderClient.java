package com.example.order.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "order")
public interface OrderClient {

    @ContractMethod
    String placeOrder(String itemId);
}
