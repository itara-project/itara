package dev.itara.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Immutable context object that travels with every request through the Itara
 * topology — within a process via ThreadLocal, and across process boundaries
 * via Itara's own propagation headers (see ContextPropagation).
 *
 * <p>itaraTraceId and itaraSpanId are generated and owned entirely by
 * ItaraContext — independent of any observer, including OTel. Every observer
 * receives these IDs on every event and may use them to correlate with other
 * observers and with this context. Observers with their own ID models (OTel,
 * Datadog, custom audit logs) maintain those separately; they are not seeded
 * from or into ItaraContext.
 *
 * <p>ThreadLocal lifecycle:
 * <ul>
 * <li>Set by the transport layer on entry (listener) or call initiation (proxy)</li>
 * <li>Always cleared in a finally block — never leaks between requests</li>
 * <li>Component code MAY read the current context via ItaraContext.current()</li>
 * <li>Component code MUST NOT set or clear the context</li>
 * </ul>
 *
 * <p>Known limitation: ThreadLocal propagation does not work with reactive
 * frameworks (Project Reactor, RxJava) that switch threads between operations.
 * Reactive support is deferred to a future itara-reactor module.
 */
public final class ItaraContext {

    // ── W3C Trace Context fields ───────────────────────────────────────────

    /** 32 hex chars — shared across the entire distributed trace */
    private final String itaraTraceId;

    /** 16 hex chars — identifies this specific span */
    private final String itaraSpanId;

    /** 16 hex chars — the caller's spanId, null for root spans */
    private final String itaraParentSpanId;

    // ── Itara-specific fields ──────────────────────────────────────────────

    /** Unique per originating request */
    private final String requestId;

    /** Business-level identifier, optionally set by the entry point caller */
    private final String correlationId;

    /** Component id where this request originated */
    private final String sourceNode;

    /** Ordered list of component ids traversed by this request so far */
    private final List<String> edgePath;

    // ── Per-thread context stack ───────────────────────────────────────────

    private static final ThreadLocal<Deque<ItaraContext>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    // ── Constructor ────────────────────────────────────────────────────────

    private ItaraContext(String itaraTraceId,
                         String itaraSpanId,
                         String itaraParentSpanId,
                         String requestId,
                         String correlationId,
                         String sourceNode,
                         List<String> edgePath) {
        this.itaraTraceId      = itaraTraceId;
        this.itaraSpanId       = itaraSpanId;
        this.itaraParentSpanId = itaraParentSpanId;
        this.requestId         = requestId;
        this.correlationId     = correlationId;
        this.sourceNode        = sourceNode;
        this.edgePath          = Collections.unmodifiableList(new ArrayList<>(edgePath));
    }

    // ── Static factory methods ─────────────────────────────────────────────

    /**
     * Creates a new root context for a request entering the system with no
     * incoming context. Generates a fresh itaraTraceId, itaraSpanId, and
     * requestId.
     *
     * @param sourceNode the id of the component where this request originated
     * @return a new root context
     */
    public static ItaraContext newRoot(String sourceNode) {
        return new ItaraContext(
                generateItaraTraceId(),
                generateItaraSpanId(),
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
     *
     * @param sourceNode    the id of the component where this request originated
     * @param correlationId the business-level correlation id
     * @return a new root context
     */
    public static ItaraContext newRoot(String sourceNode, String correlationId) {
        return new ItaraContext(
                generateItaraTraceId(),
                generateItaraSpanId(),
                null,
                generateRequestId(),
                correlationId,
                sourceNode,
                Collections.emptyList()
        );
    }

    /**
     * Creates a child context for a call crossing a component boundary.
     * Inherits itaraTraceId and requestId from the parent. Generates a new
     * itaraSpanId. Records the parent's itaraSpanId for trace reconstruction.
     *
     * @param nextComponentId the component this call is crossing into
     * @return a new child context, one hop deeper than this one
     */
    public ItaraContext newChildSpan(String nextComponentId) {
        List<String> newPath = new ArrayList<>(edgePath);
        newPath.add(nextComponentId);
        return new ItaraContext(
                this.itaraTraceId,
                generateItaraSpanId(),
                this.itaraSpanId,
                this.requestId,
                this.correlationId,
                this.sourceNode,
                newPath
        );
    }

    /**
     * Creates a caller-side child context for CALL_SENT. Inherits edgePath
     * unchanged — the path only grows when a call arrives at a node, not
     * when it departs. Only newChildSpan (used by fireCallReceived) extends
     * the path.
     *
     * @return a new caller-side child context
     */
    public ItaraContext newCallerSpan() {
        return new ItaraContext(
                this.itaraTraceId,
                generateItaraSpanId(),
                this.itaraSpanId,
                this.requestId,
                this.correlationId,
                this.sourceNode,
                this.edgePath
        );
    }

    /**
     * Creates a child context for a custom span within the current call.
     *
     * <p>Inherits all fields from the parent unchanged — custom spans are
     * sub-spans within an existing component boundary, not topology hops.
     * edgePath is not extended, sourceNode is not changed.
     *
     * <p>Used by ObservabilityFacade.openCustomSpan().
     *
     * @return a new child context for the custom span
     */
    public ItaraContext newCustomSpan() {
        return new ItaraContext(
                this.itaraTraceId,
                generateItaraSpanId(),
                this.itaraSpanId,
                this.requestId,
                this.correlationId,
                this.sourceNode,
                this.edgePath
        );
    }

    /**
     * Restores a context received from a remote caller.
     * Used by ContextPropagation when an incoming request carries Itara
     * propagation headers.
     *
     * @param itaraTraceId      the 32 hex char id shared across the entire distributed trace
     * @param itaraSpanId       the caller's own 16 hex char span id, or null (see ContextPropagation's FIRE_AND_FORGET handling)
     * @param itaraParentSpanId the caller's parent span id, or null for a root span
     * @param requestId         the id unique to the originating request
     * @param correlationId     the business-level correlation id, or null if none was set
     * @param sourceNode        the id of the component where this request originated
     * @param edgePath          the ordered list of component ids traversed by this request so far
     * @return the restored context
     */
    public static ItaraContext restore(String itaraTraceId,
                                       String itaraSpanId,
                                       String itaraParentSpanId,
                                       String requestId,
                                       String correlationId,
                                       String sourceNode,
                                       List<String> edgePath) {
        return new ItaraContext(itaraTraceId, itaraSpanId, itaraParentSpanId,
                requestId, correlationId, sourceNode, edgePath);
    }

    // ── Per-thread context stack access ───────────────────────────────────
    //
    // push/pop are called by ObservabilityFacade only — not by component code.
    // current() is the only method component code should call.
    //
    // The stack grows with every component boundary crossed on this thread
    // and shrinks as calls return — depth is bounded by call nesting depth.
    // Because the stack is thread-local, no synchronization is needed.

    /**
     * Returns the innermost active context for this thread, or null if no
     * call is in progress. This is the only method component code should call.
     *
     * @return the innermost active context for this thread, or null if none
     */
    public static ItaraContext current() {
        return STACK.get().peek();
    }

    /**
     * Pushes a new context onto this thread's stack.
     *
     * <p>Called by ObservabilityFacade before firing any event that opens a
     * scope — onCallSent, onCallReceived, onCustomSpan — and before restoring
     * an inbound context. Must always be paired with a pop() in a finally
     * block (ObservabilityFacade does this via ItaraScope.close()).
     *
     * @param ctx the context to push
     */
    public static void push(ItaraContext ctx) {
        STACK.get().push(ctx);
    }

    /**
     * Pops the innermost context from this thread's stack and returns it.
     *
     * <p>Called by ObservabilityFacade after firing whichever event closes a
     * scope — onReturnReceived, onReturnSent, onCustomSpanClosed, or
     * onInboundContextReleased. Always in a finally block — must never be
     * skipped.
     *
     * @return the popped context
     */
    public static ItaraContext pop() {
        return STACK.get().pop();
    }

    /**
     * Clears the entire context stack for this thread.
     * Safety valve only — called if an unrecoverable error leaves the stack
     * in an unknown state. Normal call paths use push/pop exclusively.
     */
    public static void clear() {
        STACK.remove();
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /**
     * Returns the 32 hex char id shared across the entire distributed trace.
     *
     * @return the 32 hex char id shared across the entire distributed trace
     */
    public String getItaraTraceId()      { return itaraTraceId; }
    /**
     * Returns the 16 hex char id identifying this specific span.
     *
     * @return the 16 hex char id identifying this specific span
     */
    public String getItaraSpanId()       { return itaraSpanId; }
    /**
     * Returns the caller's 16 hex char span id, or null for a root span.
     *
     * @return the caller's 16 hex char span id, or null for a root span
     */
    public String getItaraParentSpanId() { return itaraParentSpanId; }
    /**
     * Returns the id unique to the originating request.
     *
     * @return the id unique to the originating request
     */
    public String getRequestId()         { return requestId; }
    /**
     * Returns the business-level correlation id set by the entry point caller, or null if none was set.
     *
     * @return the business-level correlation id set by the entry point caller, or null if none was set
     */
    public String getCorrelationId()     { return correlationId; }
    /**
     * Returns the id of the component where this request originated.
     *
     * @return the id of the component where this request originated
     */
    public String getSourceNode()        { return sourceNode; }
    /**
     * Returns the ordered list of component ids traversed by this request so far; never null, may be empty.
     *
     * @return the ordered list of component ids traversed by this request so far; never null, may be empty
     */
    public List<String> getEdgePath()    { return edgePath; }

    // ── ID generation ──────────────────────────────────────────────────────

    /**
     * Generates a 32 hex char itaraTraceId (128 bits).
     *
     * @return a fresh, random itaraTraceId
     */
    public static String generateItaraTraceId() {
        UUID uuid = UUID.randomUUID();
        return String.format("%016x%016x",
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /**
     * Generates a 16 hex char itaraSpanId (64 bits).
     *
     * @return a fresh, random itaraSpanId
     */
    public static String generateItaraSpanId() {
        return String.format("%016x", UUID.randomUUID().getMostSignificantBits());
    }

    /**
     * Generates a unique request ID.
     *
     * @return a fresh, random request id
     */
    public static String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return "ItaraContext{itaraTraceId=" + itaraTraceId
                + ", itaraSpanId=" + itaraSpanId
                + ", itaraParentSpanId=" + itaraParentSpanId
                + ", requestId=" + requestId
                + ", sourceNode=" + sourceNode
                + ", edgePath=" + edgePath + "}";
    }
}
