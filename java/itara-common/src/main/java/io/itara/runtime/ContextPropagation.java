package io.itara.runtime;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Serializes and deserializes ItaraContext to and from transport headers.
 *
 * Itara uses its own dedicated headers, independent of W3C Trace Context.
 * Observers that maintain their own propagation model (e.g. OTel W3C headers)
 * handle their own headers via ItaraObserver.serializeContext() /
 * restoreContext() — ContextPropagation is only responsible for the
 * Itara-native fields.
 *
 * Header protocol:
 *   X-Itara-Trace-Id      — itaraTraceId (32 hex chars), always present
 *   X-Itara-Span-Id       — itaraSpanId  (16 hex chars), always present
 *   X-Itara-Request-Id    — requestId,   always present
 *   X-Itara-Correlation   — correlationId, omitted when null
 *   X-Itara-Source-Node   — sourceNode,    omitted when null
 *   X-Itara-Edge-Path     — comma-separated edge path, omitted when empty
 *
 * fromHeaders() returns a root context when Itara headers are absent,
 * so the dispatcher can always call restoreInboundContext() regardless
 * of whether the call originated from another Itara node.
 */
public final class ContextPropagation {

    private static final Logger log = Logger.getLogger(ContextPropagation.class.getName());

    public static final String HEADER_TRACE_ID     = "x-itara-trace-id";
    public static final String HEADER_SPAN_ID      = "x-itara-span-id";
    public static final String HEADER_REQUEST_ID   = "x-itara-request-id";
    public static final String HEADER_CORRELATION  = "x-itara-correlation";
    public static final String HEADER_SOURCE_NODE  = "x-itara-source-node";
    public static final String HEADER_EDGE_PATH    = "x-itara-edge-path";

    private static final String EDGE_SEP = ",";

    private ContextPropagation() {}

    /**
     * Serializes the context into Itara-native transport headers.
     * The returned map is merged into the outbound header map by
     * ObservabilityFacade.buildOutboundHeaders().
     */
    public static Map<String, String> toHeaders(ItaraContext ctx) {
        Map<String, String> headers = new HashMap<>();
        headers.put(HEADER_TRACE_ID,   ctx.getItaraTraceId());
        headers.put(HEADER_SPAN_ID,    ctx.getItaraSpanId());
        headers.put(HEADER_REQUEST_ID, ctx.getRequestId());
        if (ctx.getCorrelationId() != null)
            headers.put(HEADER_CORRELATION, ctx.getCorrelationId());
        if (ctx.getSourceNode() != null)
            headers.put(HEADER_SOURCE_NODE, ctx.getSourceNode());
        if (!ctx.getEdgePath().isEmpty())
            headers.put(HEADER_EDGE_PATH, String.join(EDGE_SEP, ctx.getEdgePath()));
        return headers;
    }

    /**
     * Deserializes an ItaraContext from inbound transport headers.
     *
     * The returned context represents the caller's context — it is used
     * as the parent by fireCallReceived() when creating the callee-side
     * child context. itaraParentSpanId is not propagated since it belongs
     * to the caller's trace and is not needed on the callee side.
     *
     * Returns a fresh root context when Itara headers are absent, so the
     * dispatcher can always call restoreInboundContext() regardless of
     * whether the inbound call originated from another Itara node.
     */
    public static ItaraContext fromHeaders(Map<String, String> headers) {
        String itaraTraceId = headers.get(HEADER_TRACE_ID);
        String itaraSpanId  = headers.get(HEADER_SPAN_ID);

        if (itaraTraceId == null || itaraSpanId == null) {
            // No Itara context — external call entering the system
            return ItaraContext.newRoot("external");
        }

        String requestId     = headers.getOrDefault(HEADER_REQUEST_ID,
                ItaraContext.generateRequestId());
        String correlationId = headers.get(HEADER_CORRELATION);
        String sourceNode    = headers.get(HEADER_SOURCE_NODE);
        String edgePathRaw   = headers.get(HEADER_EDGE_PATH);
        List<String> edgePath = (edgePathRaw == null || edgePathRaw.isBlank())
                ? Collections.emptyList()
                : List.of(edgePathRaw.split(EDGE_SEP, -1));

        return ItaraContext.restore(itaraTraceId, itaraSpanId, null,
                requestId, correlationId, sourceNode, edgePath);
    }
}
