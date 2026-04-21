package io.itara.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of active ItaraObserver implementations.
 *
 * Captures System.nanoTime() once per fire call and passes the same
 * timestamp to all registered observers — every observer sees an
 * identical timestamp for the same event.
 *
 * A failure in one observer must not prevent delivery to others —
 * each observer is invoked in a try/catch.
 *
 * Thread-safe: uses CopyOnWriteArrayList to allow safe concurrent reads
 * during event firing while observers are being registered at startup.
 *
 * Lives in itara-common so it is accessible to component code,
 * transport implementations, and the agent — all of which share the
 * same parent (system) classloader boundary.
 */
public final class ObserverRegistry {

    private static final ObserverRegistry INSTANCE = new ObserverRegistry();

    private final List<ItaraObserver> observers = new CopyOnWriteArrayList<>();

    private ObserverRegistry() {}

    public static ObserverRegistry instance() {
        return INSTANCE;
    }

    public void register(ItaraObserver observer) {
        observers.add(observer);
        System.out.println("[Itara] Registered observer: "
                + observer.getClass().getName());
    }

    public int size() {
        return observers.size();
    }

    // ── Event dispatch ─────────────────────────────────────────────────────

    public void fireCallSent(ItaraContext ctx,
                             String componentId,
                             String methodName) {
        long timestamp = System.nanoTime();
        for (ItaraObserver observer : observers) {
            try {
                observer.onCallSent(ctx, componentId, methodName, timestamp);
            } catch (Exception e) {
                warn(observer, "onCallSent", e);
            }
        }
    }

    public void fireCallReceived(ItaraContext ctx,
                                 String componentId,
                                 String methodName) {
        long timestamp = System.nanoTime();
        for (ItaraObserver observer : observers) {
            try {
                observer.onCallReceived(ctx, componentId, methodName, timestamp);
            } catch (Exception e) {
                warn(observer, "onCallReceived", e);
            }
        }
    }

    public void fireReturnSent(ItaraContext ctx,
                               String componentId,
                               String methodName,
                               boolean error) {
        long timestamp = System.nanoTime();
        for (ItaraObserver observer : observers) {
            try {
                observer.onReturnSent(ctx, componentId, methodName, timestamp, error);
            } catch (Exception e) {
                warn(observer, "onReturnSent", e);
            }
        }
    }

    public void fireReturnReceived(ItaraContext ctx,
                                   String componentId,
                                   String methodName,
                                   boolean error) {
        long timestamp = System.nanoTime();
        for (ItaraObserver observer : observers) {
            try {
                observer.onReturnReceived(ctx, componentId, methodName, timestamp, error);
            } catch (Exception e) {
                warn(observer, "onReturnReceived", e);
            }
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private void warn(ItaraObserver observer, String method, Exception e) {
        System.err.println("[Itara] Observer error in "
                + observer.getClass().getName()
                + "." + method + ": " + e.getMessage());
    }
}
