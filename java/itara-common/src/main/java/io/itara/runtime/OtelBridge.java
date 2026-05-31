package io.itara.runtime;

import java.util.List;

/**
 * Bridge interface between Itara's context model and OpenTelemetry.
 *
 * OTel is a built-in feature of Itara, not an implementation of the
 * ItaraObserver SPI. This interface lives in itara-common as the contract.
 * The implementation lives in itara-observability-otel and is discovered
 * at startup via META-INF/itara/otel-bridge from itara.lib.dir.
 *
 * If no implementation is found, NoOpOtelBridge is used — Itara generates
 * its own context roots and the system works without OTel. All passive
 * ItaraObserver implementations still receive all four events regardless.
 *
 * Context ownership:
 *   The bridge owns context creation and resolution entirely. The facade
 *   and decorator never create contexts directly — they fire events and
 *   use whatever context the bridge returns. This ensures OTel traceIds
 *   seed the Itara context at the right moment (first event, not before).
 *
 * Opener events (onCallSent, onCallReceived) accept a nullable context:
 *   - null means this is a root call — no incoming context exists
 *   - non-null means this is a child call — inherit from parent
 *   Both return the resolved ItaraContext to use for this call.
 *
 * Closer events (onReturnSent, onReturnReceived) receive the context
 * returned by their corresponding opener — they close the OTel span.
 */
public interface OtelBridge {

    /**
     * Fired on the caller side before dispatch.
     * Creates or inherits context and opens a CLIENT OTel span.
     *
     * @param ctx       the current context, or null if this is a root call
     * @param transport the actual transport — "direct", "http", "kafka", etc.
     * @return          the resolved context to use for this call —
     *                  may be a new root or a new child of the incoming ctx
     */
    ItaraContext onCallSent(ItaraContext ctx,
                            String componentId,
                            String methodName,
                            String transport,
                            long timestamp);

    /**
     * Fired on the callee side on arrival.
     * Creates or inherits context and opens a SERVER OTel span.
     *
     * @param ctx       the restored context from incoming headers, or null
     *                  if no incoming context was present
     * @param transport the actual transport — "direct", "http", "kafka", etc.
     * @return          the resolved context to use for the callee side
     */
    ItaraContext onCallReceived(ItaraContext ctx,
                                String componentId,
                                String methodName,
                                String transport,
                                long timestamp);

    /**
     * Fired on the callee side before response.
     * Closes the SERVER OTel span opened by onCallReceived.
     *
     * @param ctx the context returned by onCallReceived
     */
    void onReturnSent(ItaraContext ctx,
                      String componentId,
                      String methodName,
                      long timestamp,
                      boolean error);

    /**
     * Fired on the caller side on return.
     * Closes the CLIENT OTel span opened by onCallSent.
     * Records the call duration metric.
     *
     * @param ctx the context returned by onCallSent
     */
    void onReturnReceived(ItaraContext ctx,
                          String componentId,
                          String methodName,
                          long timestamp,
                          boolean error);

    /**
     * Restores a context from incoming W3C trace headers.
     * Called by transport listeners before firing onCallReceived.
     *
     * The OTel implementation restores the OTel context so the span
     * tree is correctly linked across JVMs.
     * The no-op implementation delegates to ItaraContext.restore().
     */
    ItaraContext restoreContext(String traceId,
                                String spanId,
                                String parentSpanId,
                                String requestId,
                                String correlationId,
                                String sourceNode,
                                List<String> edgePath);
}
