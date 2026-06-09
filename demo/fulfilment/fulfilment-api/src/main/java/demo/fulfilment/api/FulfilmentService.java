package demo.fulfilment.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the fulfilment component.
 * Lives in fulfilment-api.jar.
 */
@ComponentInterface(id = "fulfilment")
public interface FulfilmentService {

    @ContractMethod
    void processOrder(String orderId, String productId, int quantity);

    @ContractMethod(idempotent = true)
    void cancelOrder(String orderId);
}
