package io.itara.runtime;

/**
 * A handle returned by ObservabilityFacade when a context is opened.
 * Closing the scope fires the matching close event (onReturnSent or
 * onReturnReceived) and pops the ItaraContext stack.
 *
 * Always use in a try-with-resources block:
 *
 *   try (var scope = facade.fireCallSent(componentId, method, transport)) {
 *       try {
 *           result = invoke();
 *       } catch (Throwable t) {
 *           scope.setError(true);
 *           throw t;
 *       }
 *   }
 *
 * close() does not declare a checked exception — all observer failures
 * are caught and logged internally so the close path is always safe.
 */
public interface ItaraScope extends AutoCloseable {

    /**
     * Marks this scope as having ended with an error.
     * Must be called before close() if the invocation threw.
     */
    void setError(boolean error);

    /** Fires the matching close event and pops the context. Never throws. */
    @Override
    void close();
}
