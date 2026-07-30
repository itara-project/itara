package com.example.gateway;

import com.example.calculator.api.CalculatorRequest;
import com.example.calculator.api.CalculatorResponse;
import com.example.calculator.api.CalculatorService;
import com.example.gateway.api.GatewayClient;

/**
 * Wraps the proto-backed calculator connection behind a plain-types
 * contract of its own. From gateway's own business logic here, calling
 * calculator looks like an ordinary method call on an ordinary Java
 * interface — building a CalculatorRequest, reading a CalculatorResponse.
 * Everything about how that call actually leaves the process (HTTP),
 * what bytes it becomes (protobuf, via the proto serializer), and how the
 * response comes back is invisible from here — exactly as ADR 0019 and
 * ADR 0007 intend.
 */
public class GatewayClientImpl implements GatewayClient {

    private final CalculatorService calculator;

    public GatewayClientImpl(CalculatorService calculator) {
        this.calculator = calculator;
    }

    @Override
    public int sum(int a, int b) {
        System.out.println("[gateway] calling calculator.add(" + a + ", " + b + ")");
        CalculatorRequest request = CalculatorRequest.newBuilder()
                .setA(a)
                .setB(b)
                .build();
        CalculatorResponse response = calculator.add(request);
        System.out.println("[gateway] called calculator.add(" + a + ", " + b + ") over protobuf, result is = "
                + response.getResult());
        return response.getResult();
    }
}
