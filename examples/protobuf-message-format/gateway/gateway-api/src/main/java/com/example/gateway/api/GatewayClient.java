package com.example.gateway.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Gateway's own contract — plain int parameters and return type, no
 * message format declared (see gateway-api.itara — no [contract] section
 * at all). Only calculator's API uses protobuf in this example; gateway
 * demonstrates that a plain-types component can sit right next to one
 * without either side needing to know or care about the other's choice.
 */
@ComponentInterface(id = "gateway")
public interface GatewayClient {

    @ContractMethod(idempotent = true)
    int sum(int a, int b);
}
