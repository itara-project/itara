package demo.gateway.component;

import demo.calculator.api.CalculatorService;
import demo.gateway.api.GatewayService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

/**
 * Activator for the gateway component.
 *
 * Pulls CalculatorService from the registry — the registry returns either
 * a direct instance (collocated topology) or an HTTP proxy (remote topology).
 * This code does not change between the two topologies. That is the point.
 */
public class GatewayActivator implements ItaraActivator<GatewayService> {

    @Override
    public GatewayService activate(ItaraRegistry registry) {
        System.out.println("[GatewayActivator] Pulling calculator from registry...");
        CalculatorService calculator = registry.get("calculator", CalculatorService.class);
        System.out.println("[GatewayActivator] Creating GatewayServiceImpl");
        return new GatewayServiceImpl(calculator);
    }
}
