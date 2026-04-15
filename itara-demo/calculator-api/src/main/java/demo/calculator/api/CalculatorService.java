package demo.calculator.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the calculator component.
 * Lives in calculator-api.jar.
 * Both the gateway and the calculator depend on this jar.
 */
@ComponentInterface(id = "calculator")
public abstract class CalculatorService {

    @ContractMethod(idempotent = true)
    public abstract int add(int a, int b);
}
