package io.itara.observability.otel;

import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ItaraObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * OTel implementation of ItaraObserver.
 *
 * Span lifecycle and parent-child linkage are managed entirely via the OTel
 * Context API — no manual span-ID bookkeeping. Each opener event starts a
 * span, calls makeCurrent() to push it onto OTel's thread-local context
 * stack, and stores the resulting Scope. Each closer event pops the Scope
 * and ends the span. Because the event sequence within a thread is strictly
 * LIFO, a single per-thread Deque of pending spans is sufficient.
 *
 * onCallSent       → starts CLIENT span parented from Context.current()
 *                    → pushes span onto OTel context stack (makeCurrent)
 * onCallReceived   → starts SERVER span parented from REMOTE_PARENT if set
 *                    (remote call) or Context.current() (direct call)
 *                    → pushes span onto OTel context stack (makeCurrent)
 * onReturnSent     → pops SERVER span, closes Scope, ends span
 * onReturnReceived → pops CLIENT span, closes Scope, ends span, records metric
 *
 * serializeContext → injects current OTel context into outbound headers
 *                    via W3C propagator (traceparent / tracestate)
 * restoreContext   → extracts remote OTel context from inbound headers
 *                    via W3C propagator, stores in REMOTE_PARENT for pickup
 *                    by the next onCallReceived on this thread
 *
 * Produces:
 *   CLIENT spans  — caller side (onCallSent → onReturnReceived)
 *   SERVER spans  — callee side (onCallReceived → onReturnSent)
 *   itara.call.duration histogram — component, method, transport, error dimensions
 *
 * Span naming: "{componentId}.{methodName}"
 *
 * Discovery: META-INF/itara/observer
 *
 * SDK requirement:
 *   This implementation requires an OTel SDK to be configured via
 *   GlobalOpenTelemetry for spans and metrics to be exported. Without
 *   a configured SDK the OTel API returns no-op spans — safe to use but
 *   nothing is exported. Add an OTel SDK exporter to your application.
 */
public class OtelObserver implements ItaraObserver {

    private static final Logger log =
            Logger.getLogger(OtelObserver.class.getName());

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
    static final AttributeKey<String>  ATTR_ITARA_TRACE  =
            AttributeKey.stringKey("itara.trace.id");
    static final AttributeKey<String>  ATTR_ITARA_SPAN   =
            AttributeKey.stringKey("itara.span.id");
    static final AttributeKey<Boolean> ATTR_ERROR       =
            AttributeKey.booleanKey("error");

    // ── OTel instruments ───────────────────────────────────────────────────

    private final Tracer          tracer;
    private final DoubleHistogram durationHistogram;

    // ── Per-thread span stack ──────────────────────────────────────────────
    //
    // Pushed by opener events (onCallSent, onCallReceived) and popped by
    // their matching closer events (onReturnReceived, onReturnSent).
    // Thread-local — no synchronization needed.

    private static final ThreadLocal<Deque<PendingSpan>> SPAN_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    // ── Inbound scope ──────────────────────────────────────────────────────
    //
    // Set by restoreContext() when a remote call arrives. The extracted OTel
    // context is made current immediately via makeCurrent(), so onCallReceived
    // can unconditionally use Context.current() as the SERVER span parent —
    // no special casing for remote vs direct. The scope is closed in
    // onReturnSent() after the SERVER span scope, restoring the pre-call
    // OTel context. Always null for direct calls.

    private static final ThreadLocal<Scope> INBOUND_SCOPE = new ThreadLocal<>();

    private static final ThreadLocal<SpanContext> PENDING_LINK = new ThreadLocal<>();

    // ── W3C propagator helpers ─────────────────────────────────────────────

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                public String get(Map<String, String> c, String k) { return c.get(k); }
                public Iterable<String> keys(Map<String, String> c) { return c.keySet(); }
            };

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);

    // ── Constructor ────────────────────────────────────────────────────────

    public OtelObserver() {
        this.tracer = GlobalOpenTelemetry.getTracer(
                INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);

        Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.durationHistogram = meter
                .histogramBuilder("itara.call.duration")
                .setDescription("Duration of Itara component calls in milliseconds.")
                .setUnit("ms")
                .build();

        log.info("[Itara/OTEL] OtelObserver initialized. "
                + "Spans and metrics flow to the configured OTel SDK backend.");
    }

    // ── ItaraObserver implementation ──────────────────────────────────────────

    /**
     * Opens a CLIENT span parented from whatever is currently on OTel's
     * context stack (Context.current()). For a root call that is the root
     * context; for a nested call it is the enclosing SERVER span.
     * makeCurrent() pushes the span onto OTel's thread-local stack so the
     * next spanBuilder picks it up automatically as its parent.
     */
    @Override
    public void onCallSent(ItaraContext ctx,
                           String componentId,
                           String methodName,
                           String transport,
                           ExchangePattern exchangePattern,
                           long timestamp) {
        Span span = tracer
                .spanBuilder(componentId + "." + methodName)
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .setStartTimestamp(timestamp, TimeUnit.NANOSECONDS)
                .startSpan();

        SpanKind spanKind = exchangePattern == ExchangePattern.FIRE_AND_FORGET
                ? SpanKind.PRODUCER
                : SpanKind.CLIENT;
        Scope scope = span.makeCurrent();
        setAttributes(span, ctx, componentId, methodName, transport);
        SPAN_STACK.get().push(new PendingSpan(span, scope, timestamp, componentId, methodName, transport, spanKind));
    }

    /**
     * Opens a SERVER span. For remote calls, parents it from the context
     * extracted by restoreContext() (stored in REMOTE_PARENT). For direct
     * calls, parents it from Context.current() — which at this point is
     * the CLIENT span opened by onCallSent on the same thread.
     */
    @Override
    public void onCallReceived(ItaraContext ctx,
                               String componentId,
                               String methodName,
                               String transport,
                               ExchangePattern exchangePattern,
                               long timestamp) {
        SpanContext link = PENDING_LINK.get();
        PENDING_LINK.remove();

        SpanBuilder spanBuilder = tracer
                .spanBuilder(componentId + "." + methodName)
                .setSpanKind(SpanKind.SERVER)
                .setParent(Context.current())
                .setStartTimestamp(timestamp, TimeUnit.NANOSECONDS);

        if (link != null && link.isValid()) {
            spanBuilder.addLink(link);
        }

        Span span = spanBuilder.startSpan();
        Scope scope = span.makeCurrent();
        setAttributes(span, ctx, componentId, methodName, transport);
        SpanKind spanKind = exchangePattern == ExchangePattern.FIRE_AND_FORGET
                ? SpanKind.CONSUMER
                : SpanKind.SERVER;
        SPAN_STACK.get().push(new PendingSpan(span, scope, timestamp, componentId, methodName, transport, spanKind));
    }

    @Override
    public void onReturnSent(ItaraContext ctx,
                             String componentId,
                             String methodName,
                             long timestamp,
                             boolean error) {
        PendingSpan pending = SPAN_STACK.get().poll();
        if (pending != null) closeSpan(pending, timestamp, error);
    }

    @Override
    public void onReturnReceived(ItaraContext ctx,
                                 String componentId,
                                 String methodName,
                                 long timestamp,
                                 boolean error) {
        PendingSpan pending = SPAN_STACK.get().poll();
        if (pending != null) closeSpan(pending, timestamp, error);
    }

    @Override
    public Map<String, String> serializeContext() {
        Map<String, String> headers = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, SETTER);
        return headers;
    }

    @Override
    public void restoreContext(Map<String, String> headers, ExchangePattern exchangePattern) {
        Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.current(), headers, GETTER);

        if (exchangePattern == ExchangePattern.FIRE_AND_FORGET) {
            // The consumer is not a child of the producer — it follows from it.
            // We carry the traceId forward as a SpanLink so the relationship is
            // visible in the trace backend without asserting parent-child hierarchy.
            // The SERVER span starts from root context — no inherited parent.
            INBOUND_SCOPE.set(Context.root().makeCurrent());
            PENDING_LINK.set(SpanContext.createFromRemoteParent(
                    Span.fromContext(extracted).getSpanContext().getTraceId(),
                    Span.fromContext(extracted).getSpanContext().getSpanId(),
                    TraceFlags.getSampled(),
                    TraceState.getDefault()
            ));
        } else {
            // REQUEST_REPLY — existing behaviour, parent-child relationship
            INBOUND_SCOPE.set(extracted.makeCurrent());
        }
    }

    @Override
    public void onInboundContextReleased() {
        PENDING_LINK.remove();
        Scope inbound = INBOUND_SCOPE.get();
        if (inbound != null) {
            INBOUND_SCOPE.remove();
            inbound.close();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setAttributes(Span span, ItaraContext ctx,
                               String componentId, String methodName,
                               String transport) {
        span.setAttribute(ATTR_COMPONENT, componentId);
        span.setAttribute(ATTR_METHOD,    methodName);
        span.setAttribute(ATTR_TRANSPORT, transport);
        span.setAttribute(ATTR_ITARA_TRACE, ctx.getItaraTraceId());
        span.setAttribute(ATTR_ITARA_SPAN,  ctx.getItaraSpanId());
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
        pending.scope.close();
        pending.span.end(endNanos, TimeUnit.NANOSECONDS);

        double durationMs = (endNanos - pending.startNanos) / 1_000_000.0;
        durationHistogram.record(durationMs, Attributes.of(
                ATTR_COMPONENT, pending.componentId,
                ATTR_METHOD,    pending.methodName,
                ATTR_TRANSPORT, pending.transport,
                ATTR_SPAN_KIND, pending.kind.toString(),
                ATTR_ERROR,     error));
    }

    // ── PendingSpan ────────────────────────────────────────────────────────

    private static final class PendingSpan {
        final Span   span;
        final Scope  scope;
        final long   startNanos;
        final String componentId;
        final String methodName;
        final String transport;
        final SpanKind kind;

        PendingSpan(Span span, Scope scope, long startNanos, String componentId,
                    String methodName, String transport, SpanKind kind) {
            this.span        = span;
            this.scope       = scope;
            this.startNanos  = startNanos;
            this.componentId = componentId;
            this.methodName  = methodName;
            this.transport   = transport;
            this.kind        = kind;
        }
    }

}
