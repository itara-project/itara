package demo.calculator.api;

/**
 * Thrown when a calculator operation cannot be performed due to invalid input.
 * This is a checked exception — callers are expected to handle it.
 */
public class ArithmeticOperationException extends Exception {
    public ArithmeticOperationException(String message) {
        super(message);
    }
}
