package demo.calculator.component;

import demo.calculator.api.CalculatorService;
import topos.api.ToposActivator;
import topos.runtime.ToposRegistry;

/**
 * Activator for the calculator component.
 * Constructs and returns the implementation instance.
 * No dependencies on other components — this one is self-contained.
 */
public class CalculatorActivator implements ToposActivator<CalculatorService> {

    @Override
    public CalculatorService activate(ToposRegistry registry) {
        System.out.println("[CalculatorActivator] Creating CalculatorServiceImpl");
        return new CalculatorServiceImpl();
    }
}
