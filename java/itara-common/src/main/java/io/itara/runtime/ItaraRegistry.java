package io.itara.runtime;

import io.itara.api.ItaraActivator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The Itara component registry for this JVM slice.
 *
 * The agent populates it before any application code runs:
 *   - Remote connections:  preRegister() with a generated HTTP proxy
 *   - Local connections:   registerActivator() with the activator class
 *
 * Two explicit retrieval methods reflect two fundamentally different use cases:
 *
 *   get()             — for application code and activators. Returns the
 *                       pre-registered remote proxy, or activates and wraps
 *                       the local instance in an ObservabilityDecorator on
 *                       first call. Lazy — safe to call from activators that
 *                       depend on Spring or other frameworks initialising first.
 *
 *   getRawImplementation() — for the inbound dispatcher only. Always returns the
 *                            raw activated instance with no wrapping. The dispatcher
 *                            owns its own observability pipeline — a decorated instance
 *                            would cause double event firing.
 *
 * No ComponentFactory. No transportHandled set. The distinction between
 * "needs decoration" and "needs raw instance" is expressed in the API.
 *
 * Singleton — one registry per JVM, accessed via ItaraRegistry.instance().
 */
public class ItaraRegistry {

    private static final Logger log = Logger.getLogger(ItaraRegistry.class.getName());

    private static final ItaraRegistry INSTANCE = new ItaraRegistry();

    // Decorated instances and remote proxies — served to application code
    private final Map<String, Object> proxies = new ConcurrentHashMap<>();

    // Raw activated instances — served to the dispatcher only
    private final Map<String, Object> rawInstances = new ConcurrentHashMap<>();

    // Activator classes for local components, registered by the agent
    private final Map<String, Class<? extends ItaraActivator<?>>> activators =
            new ConcurrentHashMap<>();

    // Contract classes per component id — needed to create the observability proxy
    private final Map<String, Class<?>> contracts = new ConcurrentHashMap<>();

    // Tracks which component ids are currently being activated
    // to detect circular dependencies. Best-effort.
    private final Map<String, Thread> activating = new ConcurrentHashMap<>();

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
        proxies.put(id, proxy);
        log.info("[Itara] Pre-registered remote proxy for: " + id);
    }

    /**
     * Called by the agent to register how to activate a local component.
     * Activation is lazy — triggered on first getProxy() or getRawImplementation().
     * This preserves Spring and framework compatibility: the activator runs
     * after the application context is ready, not during premain.
     */
    public void registerActivator(String id,
                                  Class<? extends ItaraActivator<?>> activatorClass,
                                  Class<?> contractClass) {
        activators.put(id, activatorClass);
        contracts.put(id, contractClass);
        log.info("[Itara] Registered activator for: " + id + " -> " + activatorClass.getName());
    }

    // ── Application API ───────────────────────────────────────────────────────

    /**
     * Retrieve a component for use by application code or activators.
     *
     * Remote components: returns the pre-registered proxy immediately
     * (preRegister put it in the map; computeIfAbsent never fires).
     * Local components: activates on first call, wraps in ObservabilityDecorator
     * if observers are registered. Atomic — concurrent callers receive the same instance.
     *
     * @throws IllegalStateException if the component id is not registered
     *         in this JVM slice — indicates a topology config error.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String id, Class<T> type) {
        return type.cast(proxies.computeIfAbsent(id, key -> decorate(activateRaw(key), key)));
    }

    /**
     * Retrieve the raw implementation of a local component.
     *
     * For use by the inbound dispatcher ONLY. The dispatcher owns its
     * observability pipeline — it must receive the raw instance, not a
     * decorated one, or events will fire twice.
     *
     * computeIfAbsent guarantees a single instance even under concurrent
     * dispatch — all listeners share the same activated instance.
     *
     * @throws IllegalStateException if the component is not a local component
     *         registered in this JVM slice.
     */
    @SuppressWarnings("unchecked")
    public <T> T getRawImplementation(String id, Class<T> type) {
        return type.cast(rawInstances.computeIfAbsent(id, this::activateRaw));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private Object activateRaw(String id) {
        Thread current = Thread.currentThread();
        Thread already = activating.putIfAbsent(id, current);
        if (already != null && already == current) {
            throw new IllegalStateException(
                    "[Itara] Circular dependency detected while activating: " + id);
        }

        try {
            Class<? extends ItaraActivator<?>> activatorClass = activators.get(id);
            if (activatorClass == null) {
                throw new IllegalStateException(
                        "[Itara] Topology error: component '" + id
                                + "' is not registered in this JVM slice. "
                                + "Check your wiring config.");
            }

            log.info("[Itara] Activating: " + id);
            ItaraActivator<?> activator = activatorClass.getDeclaredConstructor().newInstance();
            Object instance = activator.activate(this);
            log.info("[Itara] Activated:  " + id
                    + " -> " + instance.getClass().getSimpleName());
            return instance;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "[Itara] Failed to activate component '" + id
                            + "': " + e.getMessage(), e);
        } finally {
            activating.remove(id);
        }
    }

    private Object decorate(Object raw, String id) {
        Class<?> contractClass = contracts.get(id);
        if (ObserverRegistry.instance().size() > 0 && contractClass != null) {
            return ObservabilityDecorator.wrap(
                    raw, id, contractClass,
                    Thread.currentThread().getContextClassLoader());
        }
        return raw;
    }
}
