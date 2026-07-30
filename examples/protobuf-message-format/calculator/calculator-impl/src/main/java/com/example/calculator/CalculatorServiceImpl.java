package com.example.calculator;

import com.example.calculator.api.CalculatorRequest;
import com.example.calculator.api.CalculatorResponse;
import com.example.calculator.api.CalculatorService;

/**
 * The actual business logic. Note what's absent: no serialization code, no
 * mention of "protobuf" anywhere, no awareness of how a call reached here
 * or what transport carried it. CalculatorRequest and CalculatorResponse
 * are just the parameter and return types the contract declares — protoc
 * generated them, but this class uses them exactly like any other Java
 * object with getters and a builder. That's the point of ADR 0019: message
 * format is a serializer-layer concern, invisible here.
 */
public class CalculatorServiceImpl implements CalculatorService {

    @Override
    public CalculatorResponse add(CalculatorRequest request) {
        int result = request.getA() + request.getB();
        return CalculatorResponse.newBuilder()
                .setResult(result)
                .build();
    }
}
