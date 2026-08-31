package dev.itara.runtime;

/**
 * A handle returned by ObservabilityFacade when a context is opened.
 * Closing the scope fires the matching close event (onReturnSent or
 * onReturnReceived) and pops the ItaraContext stack.
 *
 * <p>Always use in a try-with-resources block:
 *
 * <pre>{@code
 * try (var scope = facade.fireCallSent(componentId, method, transport, exchangePattern)) {
 *     try {
 *         result = invoke();
 *     } catch (Throwable t) {
 *         scope.setError(true);
 *         throw t;
 *     }
 * }
 * }</pre>
 *
 * <p>close() does not declare a checked exception — all observer failures
 * are caught and logged internally so the close path is always safe.
 */
public interface ItaraScope extends AutoCloseable {

    /**
     * Marks this scope as having ended with an error.
     * Must be called before close() if the invocation threw.
     *
     * @param error true if this scope ended with an error
     */
    void setError(boolean error);

    /** Fires the matching close event and pops the context. Never throws. */
    @Override
    void close();
}
