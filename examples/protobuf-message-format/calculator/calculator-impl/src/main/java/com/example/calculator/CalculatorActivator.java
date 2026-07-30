package com.example.calculator;

import com.example.calculator.api.CalculatorService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

public class CalculatorActivator implements ItaraActivator {

    @Override
    public CalculatorService activate(ItaraRegistry registry) {
        return new CalculatorServiceImpl();
    }
}
