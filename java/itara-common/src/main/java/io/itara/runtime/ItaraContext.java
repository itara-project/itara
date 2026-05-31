package io.itara.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable context object that travels with every request through the Itara
 * topology — within a process via ThreadLocal, and across process boundaries
 * via W3C Trace Context headers.
 *
 * ThreadLocal lifecycle:
 *   - Set by the transport layer on entry (listener) or call initiation (proxy)
 *   - Always cleared in a finally block — never leaks between requests
 *   - Component code MAY read the current context via ItaraContext.current()
 *   - Component code MUST NOT set or clear the context
 *
 * W3C Trace Context:
 *   - traceparent header carries version, traceId, spanId, flags
 *   - tracestate header carries Itara-specific fields as itara={base64-json}
 *
 * Known limitation: ThreadLocal propagation does not work with reactive
 * frameworks (Project Reactor, RxJava) that switch threads between operations.
 * Reactive support is deferred to a future itara-reactor module.
 */
public final class ItaraContext {

    // ── W3C Trace Context fields ───────────────────────────────────────────

    /** 32 hex chars — shared across the entire distributed trace */
    private final String traceId;

    /** 16 hex chars — identifies this specific span */
    private final String spanId;

    /** 16 hex chars — the caller's spanId, null for root spans */
    private final String parentSpanId;

    // ── Itara-specific fields ──────────────────────────────────────────────

    /** Unique per originating request */
    private final String requestId;

    /** Business-level identifier, optionally set by the entry point caller */
    private final String correlationId;

    /** Component id where this request originated */
    private final String sourceNode;

    /** Ordered list of component ids traversed by this request so far */
    private final List<String> edgePath;

    // ── ThreadLocal holder ─────────────────────────────────────────────────

    private static final ThreadLocal<ItaraContext> CURRENT = new ThreadLocal<>();

    // ── Constructor ────────────────────────────────────────────────────────

    private ItaraContext(String traceId,
                         String spanId,
                         String parentSpanId,
                         String requestId,
                         String correlationId,
                         String sourceNode,
                         List<String> edgePath) {
        this.traceId       = traceId;
        this.spanId        = spanId;
        this.parentSpanId  = parentSpanId;
        this.requestId     = requestId;
        this.correlationId = correlationId;
        this.sourceNode    = sourceNode;
        this.edgePath      = Collections.unmodifiableList(new ArrayList<>(edgePath));
    }

    // ── Static factory methods ─────────────────────────────────────────────

    /**
     * Creates a new root context for a request entering the system with no
     * incoming trace context. Generates fresh traceId, spanId, and requestId.
     */
    public static ItaraContext newRoot(String sourceNode) {
        return new ItaraContext(
                generateTraceId(),
                generateSpanId(),
                null,
                generateRequestId(),
                null,
                sourceNode,
                Collections.emptyList()
        );
    }

    /**
     * Creates a new root context with an explicit correlationId set by the
     * entry point caller for business-level correlation.
     */
    public static ItaraContext newRoot(String sourceNode, String correlationId) {
        return new ItaraContext(
                generateTraceId(),
                generateSpanId(),
                null,
                generateRequestId(),
                correlationId,
                sourceNode,
                Collections.emptyList()
        );
    }

    /**
     * Creates a child context for a call crossing a component boundary.
     * Inherits traceId and requestId from the parent. Generates a new spanId.
     * Records the parent's spanId for trace reconstruction.
     */
    public ItaraContext newChildSpan(String nextComponentId) {
        List<String> newPath = new ArrayList<>(edgePath);
        newPath.add(nextComponentId);
        return new ItaraContext(
                this.traceId,
                generateSpanId(),
                this.spanId,
                this.requestId,
                this.correlationId,
                this.sourceNode,
                newPath
        );
    }

    /**
     * Restores a context received from a remote caller.
     * Used by the transport listener when an incoming request carries
     * W3C Trace Context headers.
     */
    public static ItaraContext restore(String traceId,
                                       String spanId,
                                       String parentSpanId,
                                       String requestId,
                                       String correlationId,
                                       String sourceNode,
                                       List<String> edgePath) {
        return new ItaraContext(traceId, spanId, parentSpanId,
                requestId, correlationId, sourceNode, edgePath);
    }

    // ── ThreadLocal access ─────────────────────────────────────────────────

    /** Returns the current context for this thread, or null if none is set. */
    public static ItaraContext current() {
        return CURRENT.get();
    }

    /**
     * Sets the current context for this thread.
     * Called by the transport layer only — not by component code.
     */
    public static void set(ItaraContext ctx) {
        CURRENT.set(ctx);
    }

    /**
     * Clears the current context for this thread.
     * Always called in a finally block by the transport layer.
     * Must never be skipped — failure to clear causes context leaking
     * between requests on a thread pool.
     */
    public static void clear() {
        CURRENT.remove();
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public String getTraceId()      { return traceId; }
    public String getSpanId()       { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getRequestId()    { return requestId; }
    public String getCorrelationId(){ return correlationId; }
    public String getSourceNode()   { return sourceNode; }
    public List<String> getEdgePath(){ return edgePath; }

    // ── W3C traceparent formatting ─────────────────────────────────────────

    /**
     * Formats this context as a W3C traceparent header value.
     * Format: 00-{traceId}-{spanId}-01
     */
    public String toTraceparent() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /**
     * Parses a W3C traceparent header value.
     * Returns null if the value is malformed.
     */
    public static String[] parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.split("-");
        if (parts.length < 4) return null;
        // parts[0]=version, parts[1]=traceId, parts[2]=spanId, parts[3]=flags
        return parts;
    }

    // ── ID generation ──────────────────────────────────────────────────────

    /** Generates a 32 hex char trace ID (128 bits) */
    public static String generateTraceId() {
        UUID uuid = UUID.randomUUID();
        return String.format("%016x%016x",
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /** Generates a 16 hex char span ID (64 bits) */
    public static String generateSpanId() {
        return String.format("%016x", UUID.randomUUID().getMostSignificantBits());
    }

    /** Generates a unique request ID */
    public static String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return "ItaraContext{traceId=" + traceId
                + ", spanId=" + spanId
                + ", parentSpanId=" + parentSpanId
                + ", requestId=" + requestId
                + ", sourceNode=" + sourceNode
                + ", edgePath=" + edgePath + "}";
    }
}
