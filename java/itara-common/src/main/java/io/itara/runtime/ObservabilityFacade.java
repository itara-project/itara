package io.itara.runtime;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Single point of contact for all observability in the Itara runtime.
 *
 * Singleton — initialized once by the agent at startup via initialize().
 * After initialization, all proxies, dispatchers, and decorators access
 * it via instance().
 *
 * Lives in itara-common so transports and decorators can access it
 * without depending on the agent module.
 *
 * Responsibility split:
 *   ItaraContext        — owns the per-thread context stack (push/pop/current)
 *   ContextPropagation  — owns Itara-native header serialization/deserialization
 *   ObservabilityFacade — orchestrates: drives the ItaraContext stack, fans out
 *                         to all observers, captures timestamps, returns scopes
 *   ItaraObserver SPI   — receives events and manages its own internal state
 *
 * Every method that opens a context returns an ItaraScope. Call sites use
 * try-with-resources — the scope fires the matching close event and pops the
 * ItaraContext stack on exit. This makes leaked contexts structurally
 * impossible as long as scopes are used correctly.
 *
 * Typical call site shapes:
 *
 *   Proxy (direct):
 *     try (var s = facade.fireCallSent(c, m, "direct")) {
 *         try (var s2 = facade.fireCallReceived(c, m, "direct")) {
 *             try { invoke(); } catch (Throwable t) { s2.setError(true); s.setError(true); throw t; }
 *         }
 *     }
 *
 *   Proxy (remote caller):
 *     try (var s = facade.fireCallSent(c, m, transport)) {
 *         Map<String,String> headers = facade.buildOutboundHeaders();
 *         try { send(headers); } catch (Throwable t) { s.setError(true); throw t; }
 *     }
 *
 *   Dispatcher (remote callee):
 *     try (var s = facade.restoreInboundContext(headers)) {
 *         // deserialization happens here — context is already current
 *         try (var s2 = facade.fireCallReceived(c, m, transport)) {
 *             try { invoke(); } catch (Throwable t) { s2.setError(true); throw t; }
 *         }
 *     }
 */
public final class ObservabilityFacade {

    private static final Logger log = Logger.getLogger(ObservabilityFacade.class.getName());

    private static volatile ObservabilityFacade INSTANCE;

    private final ObserverRegistry registry;

    private ObservabilityFacade() {
        this.registry = ObserverRegistry.instance();
    }

    /**
     * Initializes the singleton.
     * Called once by the agent during premain, before any component
     * activators run. Must be called before instance() is used.
     */
    public static void initialize() {
        if (INSTANCE != null) {
            log.warning("[Itara] ObservabilityFacade already initialized — "
                    + "ignoring duplicate initialization.");
            return;
        }
        INSTANCE = new ObservabilityFacade();
        log.info("[Itara] ObservabilityFacade initialized.");
    }

    /**
     * Returns the singleton instance.
     *
     * @throws IllegalStateException if initialize() has not been called
     */
    public static ObservabilityFacade instance() {
        if (INSTANCE == null) {
            throw new IllegalStateException(
                    "[Itara] ObservabilityFacade not initialized. "
                            + "initialize() must be called during agent startup.");
        }
        return INSTANCE;
    }

    /** For testing only. */
    public static void resetForTest() {
        INSTANCE = null;
    }

    // ── Inbound context (callee/dispatcher side) ───────────────────────────

    /**
     * Restores the ItaraContext from inbound transport headers and pushes
     * it onto the thread's context stack. Notifies each observer via
     * restoreContext() so they can rebuild their own propagation state
     * (e.g. OTel W3C parent linkage) before fireCallReceived fires.
     *
     * The returned scope pops the context when closed. Use in a
     * try-with-resources block that wraps both deserialization and
     * fireCallReceived — this makes the context available for future
     * deserialization measurement as well as the callee span.
     *
     * @param headers the full inbound header map from the transport
     * @return a scope that pops the restored context on close
     */
    public ItaraScope restoreInboundContext(Map<String, String> headers) {
        ItaraContext ctx = ContextPropagation.fromHeaders(headers);
        ItaraContext.push(ctx);

        for (var observer : registry.getObservers()) {
            try {
                observer.restoreContext(headers);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on restoreContext: " + e.getMessage());
            }
        }
        return new InboundScope();
    }

    // ── Outbound headers (proxy/caller side, non-direct only) ─────────────

    /**
     * Assembles the outbound header map to pass to the transport.
     * Called after fireCallSent, only for non-direct transports.
     *
     * Merges Itara-native headers (itaraTraceId, itaraSpanId, requestId,
     * correlationId, sourceNode, edgePath) with per-observer headers
     * (e.g. OTel traceparent/tracestate). Observer headers are merged in
     * registration order — later registrations win on key collision.
     *
     * @return merged header map, never null
     */
    public Map<String, String> buildOutboundHeaders() {
        Map<String, String> headers = new HashMap<>(ContextPropagation.toHeaders(ItaraContext.current()));

        for (var observer : registry.getObservers()) {
            try {
                headers.putAll(observer.serializeContext());
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on serializeContext: " + e.getMessage());
            }
        }
        return headers;
    }

    // ── Caller side ────────────────────────────────────────────────────────

    /**
     * Fires CALL_SENT. Creates a child ItaraContext (or root if none is
     * active) and pushes it onto the thread's stack.
     *
     * The returned scope fires RETURN_RECEIVED and pops the context when
     * closed. Always use in a try-with-resources block.
     */
    public ItaraScope fireCallSent(String componentId,
                                   String methodName,
                                   String transport) {
        ItaraContext parent = ItaraContext.current();
        ItaraContext ctx = (parent != null)
                ? parent.newCallerSpan()
                : ItaraContext.newRoot(componentId);
        ItaraContext.push(ctx);

        long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
        for (var observer : registry.getObservers()) {
            try {
                observer.onCallSent(ctx, componentId, methodName,
                        transport, timestamp);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onCallSent: " + e.getMessage());
            }
        }
        return new CallerScope(componentId, methodName);
    }

    // ── Callee side ────────────────────────────────────────────────────────

    /**
     * Fires CALL_RECEIVED. Creates a child ItaraContext from whatever is
     * current (the restored inbound context for remote calls, the caller's
     * context for direct calls) and pushes it.
     *
     * The returned scope fires RETURN_SENT and pops the context when
     * closed. Always use in a try-with-resources block.
     */
    public ItaraScope fireCallReceived(String componentId,
                                       String methodName,
                                       String transport) {
        ItaraContext parent = ItaraContext.current();
        ItaraContext ctx = (parent != null)
                ? parent.newChildSpan(componentId)
                : ItaraContext.newRoot(componentId);
        ItaraContext.push(ctx);

        long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
        for (var observer : registry.getObservers()) {
            try {
                observer.onCallReceived(ctx, componentId, methodName,
                        transport, timestamp);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onCallReceived: " + e.getMessage());
            }
        }
        return new CalleeScope(componentId, methodName);
    }

    // ── Scope implementations ──────────────────────────────────────────────

    /** Scope returned by restoreInboundContext — pops on close, no event. */
    private final class InboundScope implements ItaraScope {
        @Override public void setError(boolean error) { /* no-op */ }

        @Override
        public void close() {
            for (var observer : registry.getObservers()) {
                try {
                    observer.onInboundContextReleased();
                } catch (Exception e) {
                    log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                            + " threw on onInboundContextReleased: " + e.getMessage());
                }
            }
            ItaraContext.pop();
        }
    }

    /** Scope returned by fireCallSent — fires RETURN_RECEIVED and pops. */
    private final class CallerScope implements ItaraScope {
        private final String componentId;
        private final String methodName;
        private boolean error = false;

        CallerScope(String componentId, String methodName) {
            this.componentId = componentId;
            this.methodName  = methodName;
        }

        @Override public void setError(boolean error) { this.error = error; }

        @Override
        public void close() {
            ItaraContext ctx = ItaraContext.current();
            long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
            for (var observer : registry.getObservers()) {
                try {
                    observer.onReturnReceived(ctx, componentId, methodName, timestamp, error);
                } catch (Exception e) {
                    log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                            + " threw on onReturnReceived: " + e.getMessage());
                }
            }
            ItaraContext.pop();
        }
    }

    /** Scope returned by fireCallReceived — fires RETURN_SENT and pops. */
    private final class CalleeScope implements ItaraScope {
        private final String componentId;
        private final String methodName;
        private boolean error = false;

        CalleeScope(String componentId, String methodName) {
            this.componentId = componentId;
            this.methodName  = methodName;
        }

        @Override public void setError(boolean error) { this.error = error; }

        @Override
        public void close() {
            ItaraContext ctx = ItaraContext.current();
            long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
            for (var observer : registry.getObservers()) {
                try {
                    observer.onReturnSent(ctx, componentId, methodName, timestamp, error);
                } catch (Exception e) {
                    log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                            + " threw on onReturnSent: " + e.getMessage());
                }
            }
            ItaraContext.pop();
        }
    }
}
