package demo.payment.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

/**
 * Contract for the payment component.
 * The implementation lives in a separate Rust process.
 */
@ComponentInterface(id = "payment")
public interface PaymentService {

    @ContractMethod
    boolean process_payment(String orderId, long amountCents, String currency);
}
