package demo.calculator.component;

import demo.calculator.api.CalculatorService;

/**
 * The calculator implementation.
 * Lives in calculator-component.jar.
 * Has no knowledge of how it is called — direct or HTTP.
 */
public class CalculatorServiceImpl implements CalculatorService {

    @Override
    public int add(int a, int b) {
        int result = a + b;
        System.out.println("[Calculator] add(" + a + ", " + b + ") = " + result);
        return result;
    }
}
