package demo.gateway.api;

import topos.api.ComponentInterface;
import topos.api.ContractMethod;

/**
 * Contract for the gateway component.
 * The gateway accepts requests from outside and delegates to calculator.
 * Lives in gateway-api.jar.
 */
@ComponentInterface(id = "gateway")
public abstract class GatewayService {

    @ContractMethod
    public abstract String calculate(int a, int b);
}
