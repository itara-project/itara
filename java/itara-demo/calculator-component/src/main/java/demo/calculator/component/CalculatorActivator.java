package demo.calculator.component;

import demo.calculator.api.CalculatorService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

import java.util.logging.Logger;

/**
 * Activator for the calculator component.
 * Constructs and returns the implementation instance.
 * No dependencies on other components — this one is self-contained.
 */
public class CalculatorActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(CalculatorActivator.class.getName());

    @Override
    public CalculatorService activate(ItaraRegistry registry) {
        log.info("[CalculatorActivator] Creating CalculatorServiceImpl");
        return new CalculatorServiceImpl();
    }
}
