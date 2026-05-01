package io.itara.runtime;

import java.util.List;

/**
 * No-op implementation of OtelBridge used when no OTel implementation
 * is found in itara.lib.dir.
 *
 * Delegates all context operations to ItaraContext directly — Itara
 * generates its own traceIds and manages its own context lifecycle.
 * The four event methods return the resolved context but produce no
 * OTel spans or metrics.
 *
 * The system works fully without OTel when this implementation is active.
 * All passive ItaraObserver implementations still receive all events
 * via ObservabilityFacade with a correctly resolved Itara context.
 */
public class NoOpOtelBridge implements OtelBridge {

    @Override
    public ItaraContext onCallSent(ItaraContext ctx,
                                   String componentId,
                                   String methodName,
                                   String transport,
                                   long timestamp) {
        if (ctx == null) {
            return ItaraContext.newRoot(componentId);
        }
        return ctx.newChildSpan(componentId);
    }

    @Override
    public ItaraContext onCallReceived(ItaraContext ctx,
                                       String componentId,
                                       String methodName,
                                       String transport,
                                       long timestamp) {
        if (ctx == null) {
            return ItaraContext.newRoot(componentId);
        }
        return ctx;
    }

    @Override
    public void onReturnSent(ItaraContext ctx,
                             String componentId,
                             String methodName,
                             long timestamp,
                             boolean error) {}

    @Override
    public void onReturnReceived(ItaraContext ctx,
                                 String componentId,
                                 String methodName,
                                 long timestamp,
                                 boolean error) {}

    @Override
    public ItaraContext restoreContext(String traceId,
                                       String spanId,
                                       String parentSpanId,
                                       String requestId,
                                       String correlationId,
                                       String sourceNode,
                                       List<String> edgePath) {
        return ItaraContext.restore(traceId, spanId, parentSpanId,
                requestId, correlationId, sourceNode, edgePath);
    }
}
