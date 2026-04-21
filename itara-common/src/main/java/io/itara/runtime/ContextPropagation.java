package io.itara.runtime;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Utilities for propagating ItaraContext across process boundaries
 * using W3C Trace Context headers.
 *
 * Header protocol:
 *   traceparent: 00-{traceId}-{spanId}-01
 *   tracestate:  itara={base64-encoded CSV of Itara-specific fields}
 *
 * The traceparent header follows the W3C Trace Context specification
 * and is understood natively by OTel, Jaeger, Zipkin, and other backends.
 *
 * The tracestate header carries Itara-specific fields that are not part
 * of the W3C spec. Other systems will ignore the itara= entry as required
 * by the W3C spec.
 *
 * tracestate itara value (base64 of pipe-delimited fields):
 *   requestId|correlationId|sourceNode|edge1,edge2,edge3
 *   Empty/absent fields are represented as empty strings between pipes.
 */
public final class ContextPropagation {

    public static final String HEADER_TRACEPARENT = "X-B3-TraceId";
    public static final String HEADER_TRACESTATE  = "X-Itara-State";

    // Use standard W3C header names
    public static final String W3C_TRACEPARENT = "traceparent";
    public static final String W3C_TRACESTATE  = "tracestate";

    private static final String ITARA_VENDOR   = "itara=";
    private static final String FIELD_SEP      = "|";
    private static final String EDGE_SEP       = ",";

    private ContextPropagation() {}

    /**
     * Formats the context as W3C traceparent and tracestate header values.
     * Returns a two-element array: [traceparent, tracestate].
     */
    public static String[] toHeaders(ItaraContext ctx) {
        String traceparent = ctx.toTraceparent();

        // Encode Itara-specific fields into tracestate
        String requestId     = nvl(ctx.getRequestId());
        String correlationId = nvl(ctx.getCorrelationId());
        String sourceNode    = nvl(ctx.getSourceNode());
        String edgePath      = ctx.getEdgePath().isEmpty()
                ? ""
                : String.join(EDGE_SEP, ctx.getEdgePath());

        String raw = requestId + FIELD_SEP + correlationId
                + FIELD_SEP + sourceNode + FIELD_SEP + edgePath;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes());
        String tracestate = ITARA_VENDOR + encoded;

        return new String[]{ traceparent, tracestate };
    }

    /**
     * Restores an ItaraContext from W3C traceparent and tracestate header values.
     * Creates a new child span — the incoming spanId becomes the parentSpanId,
     * and a new spanId is generated for this side of the call.
     *
     * Returns null if the traceparent is missing or malformed — callers should
     * create a new root context in that case.
     */
    public static ItaraContext fromHeaders(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.isBlank()) return null;

        String[] tp = ItaraContext.parseTraceparent(traceparent);
        if (tp == null) return null;

        String traceId    = tp[1];
        String parentSpan = tp[2]; // caller's spanId becomes our parentSpanId
        String spanId     = ItaraContext.generateSpanId(); // new span for this side

        // Defaults
        String requestId     = ItaraContext.generateRequestId();
        String correlationId = null;
        String sourceNode    = null;
        List<String> edgePath = Collections.emptyList();

        // Parse Itara tracestate if present
        if (tracestate != null && !tracestate.isBlank()) {
            String itaraValue = extractItaraValue(tracestate);
            if (itaraValue != null) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(itaraValue));
                    String[] fields = decoded.split("\\" + FIELD_SEP, -1);
                    if (fields.length >= 1 && !fields[0].isBlank()) requestId     = fields[0];
                    if (fields.length >= 2 && !fields[1].isBlank()) correlationId = fields[1];
                    if (fields.length >= 3 && !fields[2].isBlank()) sourceNode    = fields[2];
                    if (fields.length >= 4 && !fields[3].isBlank()) {
                        edgePath = Arrays.asList(fields[3].split(EDGE_SEP));
                    }
                } catch (Exception e) {
                    System.err.println("[Itara] Failed to parse tracestate: " + e.getMessage());
                }
            }
        }

        return ItaraContext.restore(traceId, spanId, parentSpan,
                requestId, correlationId, sourceNode, edgePath);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String extractItaraValue(String tracestate) {
        for (String entry : tracestate.split(",")) {
            entry = entry.strip();
            if (entry.startsWith(ITARA_VENDOR)) {
                return entry.substring(ITARA_VENDOR.length());
            }
        }
        return null;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
