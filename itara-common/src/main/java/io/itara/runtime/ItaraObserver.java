package io.itara.runtime;

/**
 * Observer SPI for Itara runtime events.
 *
 * Four events fire for every component interaction regardless of transport type,
 * including direct (colocated) calls. All methods have default no-op
 * implementations — implementors override only the events they care about.
 *
 * Timestamps are provided by the registry at fire time so all observers
 * receive the same value for the same event. Observers are responsible
 * for calculating derived values such as latency — store the onCallSent
 * timestamp (keyed by spanId) and subtract on onReturnReceived.
 *
 * Adding new event types in future versions is non-breaking: new default
 * methods can be added without requiring existing implementations to change.
 *
 * Lifecycle:
 *   - Implementations are discovered via META-INF/itara/observer descriptors
 *   - Multiple observers may be registered simultaneously
 *   - A failure in one observer must not affect delivery to others
 *   - Observers MUST NOT block the call path with network I/O or slow operations
 *   - Observers that forward to external systems MUST do so asynchronously
 *
 * "Emit" semantics:
 *   Emitting an event means invoking registered observer implementations
 *   synchronously at the point the event occurs. It does not mean the event
 *   has been delivered to any external monitoring system. Delivery to external
 *   systems is the observer implementation's responsibility.
 */
public interface ItaraObserver {

    /**
     * Fired on the caller side immediately before the call is dispatched.
     * Fires for both direct and transport connections.
     *
     * @param timestamp System.nanoTime() at the moment of firing
     */
    default void onCallSent(ItaraContext ctx,
                            String componentId,
                            String methodName,
                            long timestamp) {}

    /**
     * Fired on the callee side immediately upon receiving the call.
     * Fires for both direct and transport connections.
     * For direct calls, fires immediately after onCallSent with near-zero elapsed time.
     *
     * @param timestamp System.nanoTime() at the moment of firing
     */
    default void onCallReceived(ItaraContext ctx,
                                String componentId,
                                String methodName,
                                long timestamp) {}

    /**
     * Fired on the callee side immediately before the response is returned.
     * Fires for both direct and transport connections.
     *
     * @param timestamp System.nanoTime() at the moment of firing
     * @param error     true if the invocation resulted in an exception
     */
    default void onReturnSent(ItaraContext ctx,
                              String componentId,
                              String methodName,
                              long timestamp,
                              boolean error) {}

    /**
     * Fired on the caller side immediately upon receiving the response.
     * Fires for both direct and transport connections.
     * For direct calls, fires immediately after onReturnSent with near-zero elapsed time.
     *
     * @param timestamp System.nanoTime() at the moment of firing
     * @param error     true if the invocation resulted in an exception
     */
    default void onReturnReceived(ItaraContext ctx,
                                  String componentId,
                                  String methodName,
                                  long timestamp,
                                  boolean error) {}
}
