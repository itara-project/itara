package demo.gateway.component;

import demo.calculator.api.CalculatorService;
import demo.gateway.api.GatewayService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ComponentLookup;

import java.util.logging.Logger;

/**
 * Activator for the gateway component.
 *
 * Pulls CalculatorService from the registry — the registry returns either
 * a direct instance (collocated topology) or an HTTP proxy (remote topology).
 * This code does not change between the two topologies. That is the point.
 */
public class GatewayActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(GatewayActivator.class.getName());

    @Override
    public GatewayService activate() {
        log.info("[GatewayActivator] Pulling calculator from registry...");
        CalculatorService calculator = ComponentLookup.get("calculator", CalculatorService.class);
        log.info("[GatewayActivator] Creating GatewayServiceImpl");
        return new GatewayServiceImpl(calculator);
    }
}
