package dev.itara.runtime;

import java.util.Map;

/**
 * Observer SPI for Itara runtime events.
 *
 * <p>Four lifecycle events fire for every component interaction regardless of
 * transport type, including direct (colocated) calls. Two additional
 * header-exchange methods fire only when a call crosses a transport
 * boundary (i.e. transport is not "direct"). All methods have default
 * no-op implementations — implementors override only what they care about.
 *
 * <p>The four core events mark the precise instants where control crosses
 * between the application layer (component/business code) and the
 * topology layer (Itara's own machinery — proxies, transports,
 * dispatchers, serializers). Each fires exactly at the handoff, not
 * approximately around it, so together they let an observer measure the
 * full cost the topology layer adds — directly, with nothing missed and
 * nothing double-counted: onCallSent/onReturnReceived bracket the
 * caller's own handoff into and back out of the topology layer;
 * onCallReceived/onReturnSent bracket the callee's application code
 * running inside it.
 *
 * <p>Together, onCallSent/onReturnReceived form the parent span — opened as
 * close as possible to the start of everything the agent does for this
 * call, closed as close as possible to the end of it, on the caller side.
 * onCallReceived/onReturnSent form a child span, scoped to just the
 * callee's own component execution. The callee side restores the
 * caller's span first (restoreContext, as early as possible) so that
 * this child span opens as a direct child of it — when no custom spans
 * are involved in between, the callee's execution span is a direct
 * child of the caller's span.
 *
 * <p>Timestamps are provided by ObservabilityFacade at fire time so all observers
 * receive the same value for the same event. Observers are responsible for
 * calculating derived values such as latency — store the onCallSent timestamp
 * keyed by ctx.getItaraSpanId() and subtract on onReturnReceived.
 *
 * <p><b>Transport type:</b> The opener events (onCallSent, onCallReceived) include
 * the transport type string as fired by the transport implementation itself —
 * "direct", "http", "kafka", etc. This reflects the actual transport used,
 * not the configured one, which makes it a useful consistency signal as
 * well as a metric dimension. The closer events (onReturnSent,
 * onReturnReceived) do not repeat it — the transport is established at
 * call initiation and known from the opener event.
 *
 * <p><b>Header exchange:</b> serializeContext and restoreContext let an observer
 * maintain its own propagation model across process boundaries —
 * separately from ItaraContext, which propagates itaraTraceId/itaraSpanId
 * on its own via ContextPropagation regardless of which observers are
 * active. Observers that have no propagation needs of their own (they
 * rely entirely on ItaraContext) simply do not override these methods.
 *
 * <p>Neither method receives an ItaraContext as a parameter, but a context is
 * already active on the thread (via ItaraContext.current()) by the time
 * either fires. On the caller side, this is the exact same context
 * delivered to onCallSent — correlate by ctx.getItaraSpanId(), captured
 * at either firing. On the callee side it's not the same context that
 * onCallReceived later delivers: restoreContext fires against the
 * restored parent span (the caller's own context, as received), while
 * onCallReceived delivers a freshly opened child span. An observer
 * correlating its restoreContext-time state with its onCallReceived-time
 * state should key off onCallReceived's ctx.getItaraParentSpanId(), which
 * equals the span id that was active during restoreContext — not
 * ctx.getItaraSpanId(), which by then names the new child span instead.
 *
 * <p>These methods are skipped entirely for direct (colocated) calls — direct
 * calls add zero overhead beyond the four lifecycle events.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 * <li>Implementations are discovered via META-INF/itara/observer descriptors</li>
 * <li>Multiple observers may be registered simultaneously</li>
 * <li>A failure in one observer must not affect delivery to others</li>
 * <li>Observers MUST NOT block the call path with network I/O or slow operations</li>
 * <li>Observers that forward to external systems MUST do so asynchronously</li>
 * </ul>
 */
public interface ItaraObserver {

    /**
     * Fired on the caller side when control passes from the application layer into
     * the topology layer, before the call is dispatched.
     *
     * @param transport the actual transport used — "direct", "http", "kafka", etc.
     * @param timestamp nanoseconds since epoch at the moment of firing
     */
    default void onCallSent(ItaraContext ctx,
                            String componentId,
                            String methodName,
                            String transport,
                            ExchangePattern exchangePattern,
                            long timestamp) {}

    /**
     * Fired on the callee side when control passes from the topology layer
     * into the application layer, before the component implementation is invoked.
     *
     * @param transport the actual transport used — "direct", "http", "kafka", etc.
     * @param timestamp nanoseconds since epoch at the moment of firing
     */
    default void onCallReceived(ItaraContext ctx,
                                String componentId,
                                String methodName,
                                String transport,
                                ExchangePattern exchangePattern,
                                long timestamp) {}

    /**
     * Fired on the callee side when control passes from the application layer
     * back into the topology layer, after the component implementation returns
     * or throws.
     *
     * @param timestamp nanoseconds since epoch at the moment of firing
     * @param error     true if the invocation resulted in an exception
     */
    default void onReturnSent(ItaraContext ctx,
                              String componentId,
                              String methodName,
                              long timestamp,
                              boolean error) {}

    /**
     * Fired on the caller side when control passes from the topology layer
     * back into the application layer upon receiving the response.
     *
     * @param timestamp nanoseconds since epoch at the moment of firing
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
     * <p>Custom spans are sub-spans that make internal structure visible in
     * traces — for example, individual retry attempts within a failure
     * semantics implementation. They are additive and opt-in; the four
     * core events are not affected.
     *
     * <p>The context pushed for this span is already current when this fires —
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
     * <p>Always fires — even if the work inside the span threw. Observers
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
     * Fired on the caller side, but only when the call crosses a transport
     * boundary (transport is not "direct").
     *
     * <p>Fires as close to the actual transport call as possible — after
     * onCallSent, and, for a connection under failure semantics, freshly on
     * every retry attempt, immediately before headers are handed to the
     * transport. This is deliberate: it gives the widest possible window
     * for anything that runs in between (an authentication plugin
     * producing an assertion, for instance) to have already emitted its
     * own custom span, so that this observer's propagated context
     * reflects it too.
     *
     * <p>Returns header entries this observer wants attached to the outbound
     * request — for example OTel's traceparent/tracestate. ItaraContext's
     * own propagation (itaraTraceId, itaraSpanId, etc.) is handled separately
     * by the facade and must not be duplicated here.
     *
     * <p>Default returns an empty map. Implementations that rely entirely on
     * ItaraContext for propagation do not need to override this.
     *
     * @return header entries to merge into the outbound request, never null
     */
    default Map<String, String> serializeContext() {
        return Map.of();
    }

    /**
     * Fired on the callee side, but only when the call crosses a transport
     * boundary (transport is not "direct").
     *
     * <p>Fires as early as possible — before onCallReceived, before
     * deserialization, before anything else the dispatcher does with the
     * inbound call. This is deliberate: it gives the widest possible
     * window for whatever runs next (a deserializing plugin, or an
     * authentication/authorization plugin) to already have this
     * observer's restored propagation state available, in case it wants
     * to emit its own custom span against it.
     *
     * <p>Receives the full set of inbound headers — not filtered per observer —
     * so this observer can extract whatever entries it wrote via
     * serializeContext on the caller side and restore its own propagation
     * state (e.g. OTel parent span linkage).
     *
     * @param headers         the full inbound header map, never null
     * @param exchangePattern the pattern this call is being received under
     */
    default void restoreContext(Map<String, String> headers, ExchangePattern exchangePattern) {}

    /**
     * Fired on the callee side when the inbound transport scope is fully
     * released — after response serialization and transport, not just after
     * business logic. This is the counterpart to restoreContext(): any
     * observer state opened there should be cleaned up here.
     *
     * <p>Only fires for non-direct (remote) calls, matching restoreContext.
     */
    default void onInboundContextReleased() {}
}
