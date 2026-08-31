package demo.calculator.api;

import dev.itara.exceptions.ItaraReconstructibleException;
import dev.itara.exceptions.ItaraReconstructibleExceptionFactory;

import java.util.Optional;

public class CalculatorExceptionFactory implements ItaraReconstructibleExceptionFactory {
    @Override
    public String contractId() {
        return "calculator";
    }

    @Override
    public Optional<ItaraReconstructibleException> reconstruct(String errorTypeId, String message) {
        if (errorTypeId.equals(ArithmeticOperationException.class.getName())) {
            return Optional.of(new ArithmeticOperationException(message));
        }
        return Optional.empty();
    }
}
