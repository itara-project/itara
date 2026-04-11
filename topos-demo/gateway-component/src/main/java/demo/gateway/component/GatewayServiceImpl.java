package demo.gateway.component;

import demo.calculator.api.CalculatorService;
import demo.gateway.api.GatewayService;

/**
 * The gateway implementation.
 * Accepts a request, delegates to CalculatorService, prints and returns the result.
 *
 * Has no knowledge of whether CalculatorService is a direct call or HTTP.
 * The registry provides whichever the topology config dictates.
 */
public class GatewayServiceImpl extends GatewayService {

    private final CalculatorService calculator;

    public GatewayServiceImpl(CalculatorService calculator) {
        this.calculator = calculator;
    }

    @Override
    public String calculate(int a, int b) {
        System.out.println("[Gateway] Received request: add(" + a + ", " + b + ")");
        int result = calculator.add(a, b);
        String message = "The result of " + a + " + " + b + " = " + result;
        System.out.println("[Gateway] Returning: " + message);
        return message;
    }
}
