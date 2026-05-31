package demo.calculator.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the calculator component.
 * Lives in calculator-api.jar.
 * Both the gateway and the calculator depend on this jar.
 */
@ComponentInterface(id = "calculator")
public interface CalculatorService {

    @ContractMethod(idempotent = true)
    public abstract int add(int a, int b);

    /**
     * Divides a by b.
     *
     * @throws ArithmeticOperationException if b is zero — checked, caller must handle
     * @throws IllegalStateException        if the component is in an invalid state — runtime
     */
    @ContractMethod
    public abstract int divide(int a, int b) throws ArithmeticOperationException;
}

