package demo.calculator.component;

import demo.calculator.api.ArithmeticOperationException;
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

    @Override
    public int divide(int a, int b) throws ArithmeticOperationException {
        if (b == 0) {
            throw new ArithmeticOperationException("Division by zero is not allowed");
        }
        // to test unexpected runtime exceptions only
        if (a == Integer.MIN_VALUE && b == -1) {
            throw new ArithmeticException("Integer overflow: MIN_VALUE / -1");
        }
        int result = a / b;
        System.out.println("[Calculator] divide(" + a + ", " + b + ") = " + result);
        return result;
    }
}
