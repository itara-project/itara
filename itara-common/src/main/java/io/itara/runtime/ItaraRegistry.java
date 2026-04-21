package io.itara.runtime;

import io.itara.api.ItaraActivator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Itara component registry for this JVM slice.
 *
 * The agent populates it before any application code runs:
 *   - Remote connections:  preRegister() with a generated HTTP proxy
 *   - Local connections:   registerActivator() with the activator class
 *
 * Application code and activators call get() to retrieve components.
 * The first call to get() for a local component triggers its activator,
 * which may recursively call get() for its own dependencies.
 *
 * If a ComponentFactory is registered by the agent, it is called instead
 * of the default activation path — allowing the agent to wrap the instance
 * in an observability decorator before storing it. Falls back to direct
 * activation if no factory is registered, so the registry works correctly
 * without the agent (e.g. in unit tests).
 *
 * Singleton — one registry per JVM, accessed via ItaraRegistry.instance().
 */
public class ItaraRegistry {

    private static final ItaraRegistry INSTANCE = new ItaraRegistry();

    // Fully initialized instances — remote proxies and activated locals
    private final Map<String, Object> instances = new ConcurrentHashMap<>();

    // Activator classes for local components, registered by the agent
    private final Map<String, Class<? extends ItaraActivator<?>>> activators =
            new ConcurrentHashMap<>();

    // Contract classes per component id — needed by the factory to create the proxy
    private final Map<String, Class<?>> contracts = new ConcurrentHashMap<>();

    // Tracks which component ids are currently being activated
    // to detect circular dependencies. Best-effort in v1.
    private final Map<String, Thread> activating = new ConcurrentHashMap<>();

    // Optional factory registered by the agent — wraps instances in decorators
    private volatile ComponentFactory componentFactory;

    private ItaraRegistry() {}

    public static ItaraRegistry instance() {
        return INSTANCE;
    }

    // ── Agent setup API ───────────────────────────────────────────────────────

    /**
     * Called by the agent to pre-register a remote proxy before any
     * activator runs. The proxy implements the contract and routes
     * calls to the remote JVM over the transport.
     */
    public void preRegister(String id, Object proxy) {
        instances.put(id, proxy);
        System.out.println("[Itara] Pre-registered remote proxy for: " + id);
    }

    /**
     * Called by the agent to register how to activate a local component.
     * The activator is instantiated and invoked lazily on first get().
     */
    public void registerActivator(String id,
                                  Class<? extends ItaraActivator<?>> activatorClass,
                                  Class<?> contractClass) {
        activators.put(id, activatorClass);
        contracts.put(id, contractClass);
        System.out.println("[Itara] Registered activator for: " + id
                + " -> " + activatorClass.getName());
    }

    /**
     * Registers the ComponentFactory used to activate and decorate local
     * components. Called by the agent at startup after observers are loaded.
     * If not set, falls back to direct activation without decoration.
     */
    public void setComponentFactory(ComponentFactory factory) {
        this.componentFactory = factory;
    }

    // ── Application API ───────────────────────────────────────────────────────

    /**
     * Retrieve a component by id and expected type.
     *
     * If the component is already initialized (remote proxy or previously
     * activated local), returns immediately.
     *
     * If the component is local and not yet activated, invokes the factory
     * if registered, or falls back to direct activation.
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
                    "[Itara] Circular dependency detected while activating: " + id);
        }

        try {
            Object instance = instances.computeIfAbsent(id, key -> {
                Class<? extends ItaraActivator<?>> activatorClass = activators.get(key);
                if (activatorClass == null) {
                    throw new IllegalStateException(
                            "[Itara] Topology error: component '" + key
                            + "' is not registered in this JVM slice. "
                            + "Check your wiring config.");
                }

                System.out.println("[Itara] Activating: " + key);

                try {
                    if (componentFactory != null) {
                        // Agent-registered factory — activates and wraps in decorator
                        Object result = componentFactory.create(
                                activatorClass, key, contracts.get(key));
                        System.out.println("[Itara] Activated:  " + key
                                + " -> " + result.getClass().getSimpleName());
                        return result;
                    } else {
                        // Fallback — direct activation, no decoration
                        ItaraActivator<?> activator =
                                activatorClass.getDeclaredConstructor().newInstance();
                        Object result = activator.activate(ItaraRegistry.this);
                        System.out.println("[Itara] Activated:  " + key
                                + " -> " + result.getClass().getSimpleName());
                        return result;
                    }
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(
                            "[Itara] Failed to activate component: " + key, e);
                }
            });
            return type.cast(instance);
        } finally {
            activating.remove(id);
        }
    }
}
