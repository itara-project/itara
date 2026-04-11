package topos.runtime;

import topos.api.ToposActivator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Topos component registry for this JVM slice.
 *
 * The agent populates it before any application code runs:
 *   - Remote connections:  preRegister() with a generated HTTP proxy
 *   - Local connections:   registerActivator() with the activator class
 *
 * Application code and activators call get() to retrieve components.
 * The first call to get() for a local component triggers its activator,
 * which may recursively call get() for its own dependencies.
 *
 * Singleton — one registry per JVM, accessed via ToposRegistry.instance().
 */
public class ToposRegistry {

    private static final ToposRegistry INSTANCE = new ToposRegistry();

    // Fully initialized instances — remote proxies and activated locals
    private final Map<String, Object> instances = new ConcurrentHashMap<>();

    // Activator classes for local components, registered by the agent
    private final Map<String, Class<? extends ToposActivator<?>>> activators =
            new ConcurrentHashMap<>();

    // Tracks which component ids are currently being activated
    // to detect circular dependencies. Best-effort in v1.
    private final Map<String, Thread> activating = new ConcurrentHashMap<>();

    private ToposRegistry() {}

    public static ToposRegistry instance() {
        return INSTANCE;
    }

    // ── Agent setup API ───────────────────────────────────────────────────────

    /**
     * Called by the agent to pre-register a remote proxy before any
     * activator runs. The proxy implements the contract and routes
     * calls to the remote JVM over HTTP.
     */
    public void preRegister(String id, Object proxy) {
        instances.put(id, proxy);
        System.out.println("[Topos] Pre-registered remote proxy for: " + id);
    }

    /**
     * Called by the agent to register how to activate a local component.
     * The activator is instantiated and invoked lazily on first get().
     */
    public void registerActivator(String id,
                                  Class<? extends ToposActivator<?>> activatorClass) {
        activators.put(id, activatorClass);
        System.out.println("[Topos] Registered activator for: " + id
                + " -> " + activatorClass.getName());
    }

    // ── Application API ───────────────────────────────────────────────────────

    /**
     * Retrieve a component by id and expected type.
     *
     * If the component is already initialized (remote proxy or previously
     * activated local), returns immediately.
     *
     * If the component is local and not yet activated, invokes its activator.
     * The activator may call get() recursively for its own dependencies.
     *
     * @throws IllegalStateException if the component id is not registered
     *         in this JVM slice — indicates a topology config error.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String id, Class<T> type) {
        // Fast path — already initialized
        Object existing = instances.get(id);
        if (existing != null) {
            return type.cast(existing);
        }

        // Circular dependency detection (best-effort)
        Thread current = Thread.currentThread();
        Thread already = activating.putIfAbsent(id, current);
        if (already != null && already == current) {
            throw new IllegalStateException(
                    "[Topos] Circular dependency detected while activating: " + id);
        }

        try {
            // computeIfAbsent guarantees the activator runs exactly once
            // even under concurrent access
            Object instance = instances.computeIfAbsent(id, key -> {
                Class<? extends ToposActivator<?>> activatorClass = activators.get(key);
                if (activatorClass == null) {
                    throw new IllegalStateException(
                            "[Topos] Topology error: component '" + key
                            + "' is not registered in this JVM slice. "
                            + "Check your wiring config.");
                }
                try {
                    System.out.println("[Topos] Activating: " + key);
                    ToposActivator<?> activator =
                            activatorClass.getDeclaredConstructor().newInstance();
                    Object result = activator.activate(ToposRegistry.this);
                    System.out.println("[Topos] Activated:  " + key
                            + " -> " + result.getClass().getSimpleName());
                    return result;
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(
                            "[Topos] Failed to activate component: " + key, e);
                }
            });
            return type.cast(instance);
        } finally {
            activating.remove(id);
        }
    }
}
