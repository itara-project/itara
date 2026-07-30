package com.example.gateway;

import com.example.calculator.api.CalculatorService;
import com.example.gateway.api.GatewayClient;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

public class GatewayActivator implements ItaraActivator {

    @Override
    public GatewayClient activate(ItaraRegistry registry) {
        // Fetches the calculator proxy the agent already wired for the
        // gatewayNode -> calculatorNode connection (see wiring.yaml). From
        // this process, "calculator" is remote — this call actually goes
        // over HTTP, protobuf-encoded by the proto serializer, to whatever
        // process is running the calculator component.
        CalculatorService calculator = ItaraRegistry.instance().get("calculator", CalculatorService.class);

        return new GatewayClientImpl(calculator);
    }
}
