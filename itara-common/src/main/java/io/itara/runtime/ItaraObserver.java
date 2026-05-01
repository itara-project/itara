package io.itara.runtime;

/**
 * Observer SPI for Itara runtime events.
 *
 * Four events fire for every component interaction regardless of transport type,
 * including direct (colocated) calls. All methods have default no-op
 * implementations — implementors override only the events they care about.
 *
 * Timestamps are provided by ObservabilityFacade at fire time so all observers
 * receive the same value for the same event. Observers are responsible for
 * calculating derived values such as latency — store the onCallSent timestamp
 * keyed by spanId and subtract on onReturnReceived.
 *
 * Transport type:
 *   The opener events (onCallSent, onCallReceived) include the transport type
 *   string as fired by the transport implementation itself — "direct", "http",
 *   "kafka", etc. This reflects the actual transport used, not the configured
 *   one, which makes it a useful consistency signal as well as a metric dimension.
 *   The closer events (onReturnSent, onReturnReceived) do not repeat it — the
 *   transport is established at call initiation and known from the opener event.
 *
 * Lifecycle:
 *   - Implementations are discovered via META-INF/itara/observer descriptors
 *   - Multiple observers may be registered simultaneously
 *   - A failure in one observer must not affect delivery to others
 *   - Observers MUST NOT block the call path with network I/O or slow operations
 *   - Observers that forward to external systems MUST do so asynchronously
 *
 * OTel integration:
 *   OpenTelemetry is built into Itara via OtelBridge, not via this SPI.
 *   This SPI is for passive observers — logging, metrics aggregation,
 *   controller data export, audit trails, custom monitoring. Observers
 *   receive the Itara context which already carries the OTel traceId when
 *   OTel is enabled, ensuring full trace correlation with no extra work.
 */
public interface ItaraObserver {

    /**
     * Fired on the caller side immediately before the call is dispatched.
     *
     * @param transport the actual transport used — "direct", "http", "kafka", etc.
     * @param timestamp System.nanoTime() at the moment of firing
     */
    default void onCallSent(ItaraContext ctx,
                            String componentId,
                            String methodName,
                            String transport,
                            long timestamp) {}

    /**
     * Fired on the callee side immediately upon receiving the call.
     * For direct calls, fires immediately after onCallSent.
     *
     * @param transport the actual transport used — "direct", "http", "kafka", etc.
     * @param timestamp System.nanoTime() at the moment of firing
     */
    default void onCallReceived(ItaraContext ctx,
                                String componentId,
                                String methodName,
                                String transport,
                                long timestamp) {}

    /**
     * Fired on the callee side immediately before the response is returned.
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
