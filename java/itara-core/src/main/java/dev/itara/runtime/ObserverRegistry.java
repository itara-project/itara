package dev.itara.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Storage for registered {@link ItaraObserver} implementations.
 *
 * <p>This class holds observers and nothing else — it does not fire events,
 * capture timestamps, or invoke observers itself. Firing events, including
 * timestamp capture and isolating one observer's failure from the others,
 * is {@link ObservabilityFacade}'s responsibility; that's what actually
 * iterates {@link #getObservers()} and calls into each one.
 *
 * <p>Thread-safe: uses CopyOnWriteArrayList to allow safe concurrent reads
 * by the facade while observers are being registered at startup.
 *
 * <p>Lives in itara-core so it is accessible to component code,
 * transport implementations, and the agent — all of which share the
 * same parent (system) classloader boundary.
 */
public final class ObserverRegistry {

    private static final Logger log = Logger.getLogger(ObserverRegistry.class.getName());

    private static final ObserverRegistry INSTANCE = new ObserverRegistry();

    private final List<ItaraObserver> observers = new CopyOnWriteArrayList<>();

    private ObserverRegistry() {}

    /** @return the singleton registry instance */
    public static ObserverRegistry instance() {
        return INSTANCE;
    }

    /** @return the live list of registered observers, in registration order */
    public List<ItaraObserver> getObservers() {
        return observers;
    }

    /**
     * Register an observer. Called during agent startup, or by component
     * code that wants to observe events directly.
     */
    public void register(ItaraObserver observer) {
        observers.add(observer);
        log.fine("[Itara] registered observer class=" + observer.getClass().getName());
    }

    /** @return the number of currently registered observers */
    public int size() {
        return observers.size();
    }

    /** For testing only. Clears all registered observers. */
    public void resetForTest() {
        observers.clear();
    }
}
