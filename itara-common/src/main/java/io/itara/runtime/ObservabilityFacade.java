package io.itara.runtime;

import java.util.logging.Logger;

/**
 * Single point of contact for all observability in the Itara runtime.
 *
 * Singleton — initialized once by the agent at startup via initialize().
 * After initialization, all components, transports, and decorators
 * access it via instance().
 *
 * Lives in itara-common so transports and decorators can access it
 * without depending on the agent module.
 *
 * Responsibility split:
 *   ObservabilityFacade — owns ThreadLocal lifecycle, fans out to bridge
 *                         and passive observers, captures timestamps
 *   OtelBridge          — owns context creation and OTel span lifecycle,
 *                         returns resolved ItaraContext from opener events
 *   ItaraObserver SPI   — passive, receives events after the bridge,
 *                         multiple supported simultaneously
 *
 * The decorator and transport implementations call this facade with no
 * context arguments — the facade and bridge manage context entirely.
 * Callers only need to know: component, method, transport, error flag.
 *
 * Event ordering: bridge always fires before passive observers.
 * Timestamp captured once per event, shared across bridge and all observers.
 */
public final class ObservabilityFacade {

    private static final Logger log =
            Logger.getLogger(ObservabilityFacade.class.getName());

    private static volatile ObservabilityFacade INSTANCE;

    private final OtelBridge       bridge;
    private final ObserverRegistry registry;

    private ObservabilityFacade(OtelBridge bridge) {
        this.bridge   = bridge;
        this.registry = ObserverRegistry.instance();
    }

    /**
     * Initializes the singleton with the discovered OtelBridge.
     * Called once by the agent during premain, before any component
     * activators run. Must be called before instance() is used.
     */
    public static void initialize(OtelBridge bridge) {
        if (INSTANCE != null) {
            log.warning("[Itara] ObservabilityFacade already initialized — "
                    + "ignoring duplicate initialization.");
            return;
        }
        INSTANCE = new ObservabilityFacade(bridge);
        log.info("[Itara] ObservabilityFacade initialized with bridge: "
                + bridge.getClass().getSimpleName());
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

    // ── Context restoration — for transport listeners ──────────────────────

    /**
     * Restores context from incoming W3C headers.
     * Called by transport listeners before fireCallReceived.
     * Returns null if no valid incoming context — bridge will create a root.
     */
    public ItaraContext restoreContext(String traceparent, String tracestate) {
        // ContextPropagation.fromHeaders handles parsing and child span creation
        return ContextPropagation.fromHeaders(traceparent, tracestate);
    }

    // ── Caller side ────────────────────────────────────────────────────────

    /**
     * Fires CALL_SENT. The bridge resolves the context (root or child)
     * and opens a CLIENT OTel span. The resolved context is stored in
     * ThreadLocal and returned for use in the finally block.
     *
     * @return the resolved context — must be passed to fireReturnReceived
     */
    public ItaraContext fireCallSent(String componentId,
                                     String methodName,
                                     String transport) {
        long timestamp = System.nanoTime();
        ItaraContext current = ItaraContext.current();

        // Bridge resolves context: null = root, non-null = child
        ItaraContext resolved = bridge.onCallSent(
                current, componentId, methodName, transport, timestamp);
        ItaraContext.set(resolved);

        for (var observer : registry.getObservers()) {
            try {
                observer.onCallSent(resolved, componentId, methodName,
                        transport, timestamp);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onCallSent: " + e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * Fires RETURN_RECEIVED and restores the previous context.
     *
     * @param callCtx     the context returned by fireCallSent
     * @param previousCtx the context that was active before fireCallSent,
     *                    null if fireCallSent created a root context
     */
    public void fireReturnReceived(ItaraContext callCtx,
                                   ItaraContext previousCtx,
                                   String componentId,
                                   String methodName,
                                   boolean error) {
        long timestamp = System.nanoTime();
        bridge.onReturnReceived(callCtx, componentId, methodName, timestamp, error);

        for (var observer : registry.getObservers()) {
            try {
                observer.onReturnReceived(callCtx, componentId, methodName,
                        timestamp, error);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onReturnReceived: " + e.getMessage());
            }
        }

        // Restore previous context — clear if we created a root
        if (previousCtx == null) {
            ItaraContext.clear();
        } else {
            ItaraContext.set(previousCtx);
        }
    }

    // ── Callee side ────────────────────────────────────────────────────────

    /**
     * Fires CALL_RECEIVED. The bridge resolves the context and opens a
     * SERVER OTel span. The resolved context is stored in ThreadLocal
     * and returned for use in the finally block.
     *
     * @param incomingCtx context restored from W3C headers, or null if none
     * @return            the resolved context — must be passed to fireReturnSent
     */
    public ItaraContext fireCallReceived(ItaraContext incomingCtx,
                                         String componentId,
                                         String methodName,
                                         String transport) {
        long timestamp = System.nanoTime();
        ItaraContext resolved = bridge.onCallReceived(
                incomingCtx, componentId, methodName, transport, timestamp);
        ItaraContext.set(resolved);

        for (var observer : registry.getObservers()) {
            try {
                observer.onCallReceived(resolved, componentId, methodName,
                        transport, timestamp);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onCallReceived: " + e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * Fires RETURN_SENT and clears the context.
     * Always called in a finally block by the transport listener.
     *
     * @param callCtx the context returned by fireCallReceived
     */
    public void fireReturnSent(ItaraContext callCtx,
                               String componentId,
                               String methodName,
                               boolean error) {
        long timestamp = System.nanoTime();
        bridge.onReturnSent(callCtx, componentId, methodName, timestamp, error);

        for (var observer : registry.getObservers()) {
            try {
                observer.onReturnSent(callCtx, componentId, methodName,
                        timestamp, error);
            } catch (Exception e) {
                log.warning("[Itara] Observer " + observer.getClass().getSimpleName()
                        + " threw on onReturnSent: " + e.getMessage());
            }
        }

        ItaraContext.clear();
    }
}
