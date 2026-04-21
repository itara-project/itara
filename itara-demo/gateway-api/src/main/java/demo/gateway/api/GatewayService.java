package demo.gateway.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the gateway component.
 * The gateway accepts requests from outside and delegates to calculator.
 * Lives in gateway-api.jar.
 */
@ComponentInterface(id = "gateway")
public interface GatewayService {

    @ContractMethod
    String calculate(int a, int b);
}
