package io.itara.runtime;

import java.util.Map;

/**
 * Observer SPI for Itara runtime events.
 *
 * Four lifecycle events fire for every component interaction regardless of
 * transport type, including direct (colocated) calls. Two additional
 * header-exchange methods fire only when a call crosses a transport
 * boundary (i.e. transport is not "direct"). All methods have default
 * no-op implementations — implementors override only what they care about.
 *
 * Timestamps are provided by ObservabilityFacade at fire time so all observers
 * receive the same value for the same event. Observers are responsible for
 * calculating derived values such as latency — store the onCallSent timestamp
 * keyed by ctx.getItaraSpanId() and subtract on onReturnReceived.
 *
 * Transport type:
 *   The opener events (onCallSent, onCallReceived) include the transport type
 *   string as fired by the transport implementation itself — "direct", "http",
 *   "kafka", etc. This reflects the actual transport used, not the configured
 *   one, which makes it a useful consistency signal as well as a metric dimension.
 *   The closer events (onReturnSent, onReturnReceived) do not repeat it — the
 *   transport is established at call initiation and known from the opener event.
 *
 * Header exchange:
 *   serializeContext and restoreContext let an observer maintain its own
 *   propagation model across process boundaries — separately from
 *   ItaraContext, which propagates itaraTraceId/itaraSpanId on its own via
 *   ContextPropagation regardless of which observers are active. Observers
 *   that have no propagation needs of their own (they rely entirely on
 *   ItaraContext) simply do not override these methods.
 *
 *   Neither method receives an ItaraContext — the context for this call was
 *   already delivered via onCallSent/onCallReceived, immediately before
 *   serializeContext/restoreContext fire. Observers needing to correlate
 *   the two should capture whatever they need at that point, keyed by
 *   ctx.getItaraSpanId().
 *
 *   These methods are skipped entirely for direct (colocated) calls — direct
 *   calls add zero overhead beyond the four lifecycle events.
 *
 * Lifecycle:
 *   - Implementations are discovered via META-INF/itara/observer descriptors
 *   - Multiple observers may be registered simultaneously
 *   - A failure in one observer must not affect delivery to others
 *   - Observers MUST NOT block the call path with network I/O or slow operations
 *   - Observers that forward to external systems MUST do so asynchronously
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
                            ExchangePattern exchangePattern,
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
                                ExchangePattern exchangePattern,
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

    /**
     * Fired when a custom span is opened within an existing call scope.
     *
     * Custom spans are sub-spans that make internal structure visible in
     * traces — for example, individual retry attempts within a failure
     * semantics implementation. They are additive and opt-in; the four
     * core events are not affected.
     *
     * The context pushed for this span is already current when this fires —
     * its itaraParentSpanId points to the span that was active when
     * openCustomSpan() was called. Observers that maintain their own span
     * model (e.g. OTel) should open a child span here and store it keyed
     * by ctx.getItaraSpanId() for retrieval on onCustomSpanClosed.
     *
     * @param ctx        the new child context for this custom span
     * @param name       a short descriptive name, e.g. "retry-attempt"
     * @param attributes freeform key-value pairs describing the span,
     *                   e.g. {"attempt": "2"}. Never null, may be empty.
     * @param timestamp  nanoseconds since epoch at the moment of opening
     */
    default void onCustomSpan(ItaraContext ctx,
                              String name,
                              Map<String, String> attributes,
                              long timestamp) {}

    /**
     * Fired when a custom span is closed.
     *
     * Always fires — even if the work inside the span threw. Observers
     * should end whatever span they opened in onCustomSpan, keyed by
     * ctx.getItaraSpanId().
     *
     * @param ctx       the same context that was passed to onCustomSpan
     * @param name      the same name that was passed to onCustomSpan
     * @param timestamp nanoseconds since epoch at the moment of closing
     * @param error     true if the span closed with an error
     */
    default void onCustomSpanClosed(ItaraContext ctx,
                                    String name,
                                    long timestamp,
                                    boolean error) {}

    /**
     * Fired on the caller side immediately after onCallSent, but only when
     * the call crosses a transport boundary (transport is not "direct").
     *
     * Returns header entries this observer wants attached to the outbound
     * request — for example OTel's traceparent/tracestate. ItaraContext's
     * own propagation (itaraTraceId, itaraSpanId, etc.) is handled separately
     * by the facade and must not be duplicated here.
     *
     * Default returns an empty map. Implementations that rely entirely on
     * ItaraContext for propagation do not need to override this.
     *
     * @return header entries to merge into the outbound request, never null
     */
    default Map<String, String> serializeContext() {
        return Map.of();
    }

    /**
     * Fired on the callee side immediately before onCallReceived, but only
     * when the call crosses a transport boundary (transport is not "direct").
     *
     * Receives the full set of inbound headers — not filtered per observer —
     * so this observer can extract whatever entries it wrote via
     * serializeContext on the caller side and restore its own propagation
     * state (e.g. OTel parent span linkage) before onCallReceived fires.
     *
     * @param headers the full inbound header map, never null
     */
    default void restoreContext(Map<String, String> headers, ExchangePattern exchangePattern) {}

    /**
     * Fired on the callee side when the inbound transport scope is fully
     * released — after response serialization and transport, not just after
     * business logic. This is the counterpart to restoreContext(): any
     * observer state opened there should be cleaned up here.
     *
     * Only fires for non-direct (remote) calls, matching restoreContext.
     */
    default void onInboundContextReleased() {}
}
