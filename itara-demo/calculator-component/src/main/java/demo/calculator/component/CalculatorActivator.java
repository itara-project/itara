package demo.calculator.component;

import demo.calculator.api.CalculatorService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

/**
 * Activator for the calculator component.
 * Constructs and returns the implementation instance.
 * No dependencies on other components — this one is self-contained.
 */
public class CalculatorActivator implements ItaraActivator<CalculatorService> {

    @Override
    public CalculatorService activate(ItaraRegistry registry) {
        System.out.println("[CalculatorActivator] Creating CalculatorServiceImpl");
        return new CalculatorServiceImpl();
    }
}
