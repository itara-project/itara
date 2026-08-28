package com.example.calculator.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

/**
 * The calculator API's contract. This is a plain, hand-written interface —
 * message-format = "protobuf" (see calculator-api.itara) changes what
 * CalculatorRequest and CalculatorResponse are (protoc-generated, from
 * calculator.proto), not the shape of this interface itself (ADR 0019).
 * Nothing about this file, or the wiring/serialization machinery that
 * calls it, is protobuf-specific — it's an ordinary Itara contract.
 */
@ComponentInterface(id = "calculator")
public interface CalculatorService {

    @ContractMethod(idempotent = true)
    CalculatorResponse add(CalculatorRequest request);
}
