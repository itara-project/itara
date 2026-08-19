package com.example.calculator;

import com.example.calculator.api.CalculatorService;
import io.itara.api.ItaraActivator;

public class CalculatorActivator implements ItaraActivator {

    @Override
    public CalculatorService activate() {
        return new CalculatorServiceImpl();
    }
}
