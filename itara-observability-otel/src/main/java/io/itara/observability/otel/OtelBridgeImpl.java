package io.itara.observability.otel;

import io.itara.runtime.ItaraContext;
import io.itara.runtime.OtelBridge;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * OTel implementation of OtelBridge.
 *
 * Context is fully managed inside the four event methods — no separate
 * root context creation, no ghost spans. The first event for a call
 * creates the OTel span and seeds the Itara context from it. The closing
 * event closes the span and records metrics.
 *
 * onCallSent (null ctx)    → creates OTel root span → seeds Itara context
 * onCallSent (non-null)    → creates OTel child span → creates child context
 * onCallReceived (any)     → creates OTel SERVER span → uses/creates context
 * onReturnSent             → closes SERVER span
 * onReturnReceived         → closes CLIENT span, records duration metric
 *
 * Produces:
 *   CLIENT spans  — caller side (onCallSent → onReturnReceived)
 *   SERVER spans  — callee side (onCallReceived → onReturnSent)
 *   itara.call.duration histogram — component, method, transport, error dimensions
 *
 * Span naming: "{componentId}.{methodName}"
 *
 * Discovery: META-INF/itara/otel-bridge
 *
 * SDK requirement:
 *   This implementation requires an OTel SDK to be configured via
 *   GlobalOpenTelemetry for spans and metrics to be exported. Without
 *   a configured SDK, the OTel API returns no-op implementations that
 *   do not generate new span IDs — Itara detects this and falls back
 *   to its own ID generation to maintain trace correctness.
 *   Observability data (spans, metrics) will NOT be exported without
 *   a configured SDK — add an OTel SDK exporter to your application.
 */
public class OtelBridgeImpl implements OtelBridge {

    private static final Logger log =
            Logger.getLogger(OtelBridgeImpl.class.getName());

    private static final String INSTRUMENTATION_NAME    = "io.itara";
    private static final String INSTRUMENTATION_VERSION = "1.0";

    // ── Attribute keys ─────────────────────────────────────────────────────

    static final AttributeKey<String>  ATTR_COMPONENT   =
            AttributeKey.stringKey("itara.component");
    static final AttributeKey<String>  ATTR_METHOD      =
            AttributeKey.stringKey("itara.method");
    static final AttributeKey<String>  ATTR_TRANSPORT   =
            AttributeKey.stringKey("itara.transport");
    static final AttributeKey<String>  ATTR_REQUEST_ID  =
            AttributeKey.stringKey("itara.request.id");
    static final AttributeKey<String>  ATTR_CORRELATION =
            AttributeKey.stringKey("itara.correlation");
    static final AttributeKey<String>  ATTR_SOURCE_NODE =
            AttributeKey.stringKey("itara.source.node");
    static final AttributeKey<String>  ATTR_EDGE_PATH   =
            AttributeKey.stringKey("itara.edge.path");
    static final AttributeKey<String>  ATTR_SPAN_KIND   =
            AttributeKey.stringKey("itara.span.kind");
    static final AttributeKey<Boolean> ATTR_ERROR       =
            AttributeKey.booleanKey("error");

    // ── OTel instruments ───────────────────────────────────────────────────

    private final Tracer          tracer;
    private final DoubleHistogram durationHistogram;

    // ── Pending spans keyed by Itara spanId ───────────────────────────────

    private final ConcurrentHashMap<String, PendingSpan> pendingCallerSpans
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSpan> pendingCalleeSpans
            = new ConcurrentHashMap<>();

    // ── W3C propagator ─────────────────────────────────────────────────────

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                public String get(Map<String, String> c, String k) { return c.get(k); }
                public Iterable<String> keys(Map<String, String> c) { return c.keySet(); }
            };

    // ── Constructor ────────────────────────────────────────────────────────

    public OtelBridgeImpl() {
        this.tracer = GlobalOpenTelemetry.getTracer(
                INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);

        Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.durationHistogram = meter
                .histogramBuilder("itara.call.duration")
                .setDescription("Duration of Itara component calls in milliseconds.")
                .setUnit("ms")
                .build();

        log.info("[Itara/OTEL] OtelBridge initialized. "
                + "Spans and metrics flow to the configured OTel SDK backend.");
    }

    // ── OtelBridge implementation ──────────────────────────────────────────

    /**
     * Opens a CLIENT span and returns the resolved Itara context.
     *
     * If ctx is null, this is a root call — OTel generates a root span
     * and its traceId seeds the new Itara context. No separate root span
     * is created before this point, so no ghost spans appear in the backend.
     *
     * If ctx is non-null, this is a child call — a child OTel span is
     * created linked to the parent via W3C traceparent.
     */
    @Override
    public ItaraContext onCallSent(ItaraContext ctx,
                                   String componentId,
                                   String methodName,
                                   String transport,
                                   long timestamp) {
        Context otelParent = (ctx == null)
                ? Context.root()
                : buildOtelContext(ctx);

        Span span = tracer
                .spanBuilder(componentId + "." + methodName)
                .setSpanKind(SpanKind.CLIENT)
                .setParent(otelParent)
                .setStartTimestamp(timestamp, TimeUnit.NANOSECONDS)
                .startSpan();


        String otelSpanId   = span.getSpanContext().getSpanId();
        String parentSpanId = ctx != null ? ctx.getSpanId() : null;

        // Seed Itara context from OTel span — this is where the OTel traceId
        // enters the Itara context. No ghost span needed.
        boolean realSdk = span.getSpanContext().isValid()
                && (parentSpanId == null || !otelSpanId.equals(parentSpanId));

        String traceId = realSdk
                ? span.getSpanContext().getTraceId()
                : (ctx != null ? ctx.getTraceId() : ItaraContext.generateTraceId());
        String spanId = realSdk
                ? otelSpanId
                : ItaraContext.generateSpanId();

        final List<String> newEdgePath = new ArrayList<>();
        if (ctx != null) newEdgePath.addAll(ctx.getEdgePath());
        newEdgePath.add(componentId);
        ItaraContext resolved = ItaraContext.restore(
                traceId, spanId, parentSpanId,
                ctx != null ? ctx.getRequestId() : ItaraContext.generateRequestId(),
                ctx != null ? ctx.getCorrelationId() : null,
                ctx != null ? ctx.getSourceNode() : componentId,
                newEdgePath);

        setAttributes(span, resolved, componentId, methodName, transport);
        pendingCallerSpans.put(resolved.getSpanId(),
                new PendingSpan(span, timestamp, componentId, methodName,
                        transport, "CLIENT"));

        return resolved;
    }

    /**
     * Opens a SERVER span and returns the resolved Itara context.
     *
     * If ctx is null, no incoming W3C context was present — this is a
     * root entry on the callee side. OTel generates a root span.
     * If ctx is non-null, the context was restored from incoming headers —
     * the SERVER span is linked to the caller's span.
     */
    @Override
    public ItaraContext onCallReceived(ItaraContext ctx,
                                       String componentId,
                                       String methodName,
                                       String transport,
                                       long timestamp) {
        Context otelParent = (ctx == null)
                ? Context.root()
                : buildOtelContext(ctx);

        Span span = tracer
                .spanBuilder(componentId + "." + methodName)
                .setSpanKind(SpanKind.SERVER)
                .setParent(otelParent)
                .setStartTimestamp(timestamp, TimeUnit.NANOSECONDS)
                .startSpan();

        String otelSpanId   = span.getSpanContext().getSpanId();
        String parentSpanId = ctx != null ? ctx.getSpanId() : null;

        boolean realSdk = span.getSpanContext().isValid()
                && (parentSpanId == null || !otelSpanId.equals(parentSpanId));

        String traceId = realSdk
                ? span.getSpanContext().getTraceId()
                : (ctx != null ? ctx.getTraceId() : ItaraContext.generateTraceId());
        String spanId = realSdk
                ? otelSpanId
                : ItaraContext.generateSpanId();

        ItaraContext resolved = ItaraContext.restore(
                traceId, spanId, parentSpanId,
                ctx != null ? ctx.getRequestId() : ItaraContext.generateRequestId(),
                ctx != null ? ctx.getCorrelationId() : null,
                ctx != null ? ctx.getSourceNode() : componentId,
                ctx != null ? ctx.getEdgePath() : List.of());

        setAttributes(span, resolved, componentId, methodName, transport);
        pendingCalleeSpans.put(resolved.getSpanId(),
                new PendingSpan(span, timestamp, componentId, methodName,
                        transport, "SERVER"));

        return resolved;
    }

    @Override
    public void onReturnSent(ItaraContext ctx,
                             String componentId,
                             String methodName,
                             long timestamp,
                             boolean error) {
        if (ctx == null) return;
        PendingSpan pending = pendingCalleeSpans.remove(ctx.getSpanId());
        if (pending != null) closeSpan(pending, timestamp, error);
    }

    @Override
    public void onReturnReceived(ItaraContext ctx,
                                 String componentId,
                                 String methodName,
                                 long timestamp,
                                 boolean error) {
        if (ctx == null) return;
        PendingSpan pending = pendingCallerSpans.remove(ctx.getSpanId());
        if (pending != null) closeSpan(pending, timestamp, error);
    }

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

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setAttributes(Span span, ItaraContext ctx,
                               String componentId, String methodName,
                               String transport) {
        span.setAttribute(ATTR_COMPONENT, componentId);
        span.setAttribute(ATTR_METHOD,    methodName);
        span.setAttribute(ATTR_TRANSPORT, transport);
        if (ctx.getRequestId() != null)
            span.setAttribute(ATTR_REQUEST_ID, ctx.getRequestId());
        if (ctx.getCorrelationId() != null)
            span.setAttribute(ATTR_CORRELATION, ctx.getCorrelationId());
        if (ctx.getSourceNode() != null)
            span.setAttribute(ATTR_SOURCE_NODE, ctx.getSourceNode());
        if (!ctx.getEdgePath().isEmpty())
            span.setAttribute(ATTR_EDGE_PATH,
                    String.join(" -> ", ctx.getEdgePath()));
    }

    private void closeSpan(PendingSpan pending, long endNanos, boolean error) {
        if (error) pending.span.setStatus(StatusCode.ERROR);
        pending.span.end(endNanos, TimeUnit.NANOSECONDS);

        double durationMs = (endNanos - pending.startNanos) / 1_000_000.0;
        durationHistogram.record(durationMs, Attributes.of(
                ATTR_COMPONENT, pending.componentId,
                ATTR_METHOD,    pending.methodName,
                ATTR_TRANSPORT, pending.transport,
                ATTR_SPAN_KIND, pending.kind,
                ATTR_ERROR,     error));
    }

    /**
     * Builds the OTel parent context from a non-null Itara context.
     * Uses W3C traceparent so the OTel span tree is correctly linked.
     */
    private Context buildOtelContext(ItaraContext ctx) {
        String traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01";
        return W3CTraceContextPropagator.getInstance().extract(
                Context.root(),
                Map.of("traceparent", traceparent),
                GETTER);
    }

    // ── PendingSpan ────────────────────────────────────────────────────────

    private static final class PendingSpan {
        final Span   span;
        final long   startNanos;
        final String componentId;
        final String methodName;
        final String transport;
        final String kind;

        PendingSpan(Span span, long startNanos, String componentId,
                    String methodName, String transport, String kind) {
            this.span        = span;
            this.startNanos  = startNanos;
            this.componentId = componentId;
            this.methodName  = methodName;
            this.transport   = transport;
            this.kind        = kind;
        }
    }
}
