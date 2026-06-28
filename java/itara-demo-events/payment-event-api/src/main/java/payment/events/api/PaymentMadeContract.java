package payment.events.api;

import io.itara.api.EventContractInterface;

@EventContractInterface(id = "payment-made")
public interface PaymentMadeContract {
    void onPaymentMade(String orderId, String customerId, double amount);
}
