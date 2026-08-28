package demo.payment.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

/**
 * Contract for the payment component.
 * The implementation lives in a separate Rust process.
 */
@ComponentInterface(id = "payment")
public interface PaymentService {

    @ContractMethod
    boolean process_payment(String orderId, long amountCents, String currency);
}
