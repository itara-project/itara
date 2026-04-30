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

    public List<ItaraObserver> getObservers() {
        return observers;
    }

    public void register(ItaraObserver observer) {
        observers.add(observer);
        System.out.println("[Itara] Registered observer: "
                + observer.getClass().getName());
    }

    public int size() {
        return observers.size();
    }
}
