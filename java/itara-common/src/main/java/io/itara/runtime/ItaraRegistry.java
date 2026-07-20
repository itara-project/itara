package io.itara.runtime;

import io.itara.api.ItaraActivator;

import java.util.Map;
import java.util.Set;
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
    private final Map<String, Class<? extends ItaraActivator>> activators =
            new ConcurrentHashMap<>();

    // Classloader to activate each local component under, and to set as
    // TCCL for every inbound call to it. Defaults to the classloader that
    // was current when registerActivator() was called (shared mode: the
    // system classloader) unless explicitly overridden for isolated mode.
    private final Map<String, ClassLoader> classLoaders = new ConcurrentHashMap<>();

    // Contract classes per component id — needed to create the observability proxy
    private final Map<String, Class<?>> contracts = new ConcurrentHashMap<>();

    // Tracks which component ids are currently being activated
    // to detect circular dependencies. Best-effort.
    private final Map<String, Thread> activating = new ConcurrentHashMap<>();

    private final Map<String, String> aliases = new ConcurrentHashMap<>();

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
        log.fine("[Itara] registered remote proxy contract=" + id);
    }

    /**
     * Called by the agent to register how to activate a local component.
     * Activation is lazy — triggered on first getProxy() or getRawImplementation().
     * This preserves Spring and framework compatibility: the activator runs
     * after the application context is ready, not during premain.
     */
    public void registerActivator(String id,
                                  Class<? extends ItaraActivator> activatorClass,
                                  Class<?> contractClass) {
        registerActivator(id, activatorClass, contractClass, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Isolated-mode variant — explicitly pins the component to the
     * classloader it should be activated under and dispatched to on
     * every inbound call (see ItaraDispatcher).
     */
    public void registerActivator(String id,
                                  Class<? extends ItaraActivator> activatorClass,
                                  Class<?> contractClass,
                                  ClassLoader classLoader) {
        activators.put(id, activatorClass);
        contracts.put(id, contractClass);
        classLoaders.put(id, classLoader);
        log.fine("[Itara] registered activator component=" + id + " class=" + activatorClass.getName()
                + " classLoader=" + classLoader);
    }

    /**
     * Registers an alias so that lookups by aliasId delegate to canonicalId.
     * Used to map event contract ids to consumer component ids.
     * e.g. "order-events/order-placed" -> "order-consumer"
     */
    public void registerAlias(String aliasId, String canonicalId) {
        aliases.put(aliasId, canonicalId);
        log.fine("[Itara] registered alias id=" + aliasId + " canonical=" + canonicalId);
    }

    /**
     * Returns the classloader a local component was registered under, or
     * null if none was recorded (component not registered, or registered
     * via a path that predates this — treat null as "leave TCCL alone").
     */
    public ClassLoader getComponentClassLoader(String id) {
        String resolvedId = aliases.getOrDefault(id, id);
        return classLoaders.get(resolvedId);
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
        // Resolve alias if present — event contract ids map to component ids
        String resolvedId = aliases.getOrDefault(id, id);
        return type.cast(proxies.computeIfAbsent(resolvedId, key -> decorate(activateRaw(key), key)));
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
        String resolvedId = aliases.getOrDefault(id, id);
        return type.cast(rawInstances.computeIfAbsent(resolvedId, this::activateRaw));
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
            Class<? extends ItaraActivator> activatorClass = activators.get(id);
            if (activatorClass == null) {
                throw new IllegalStateException(
                        "[Itara] Topology error: component '" + id
                                + "' is not registered in this JVM slice. "
                                + "Check your wiring config.");
            }

            log.fine("[Itara] activating component=" + id);
            ClassLoader componentCl = classLoaders.get(id);
            Thread currentThread = Thread.currentThread();
            ClassLoader previousCl = currentThread.getContextClassLoader();
            log.info("[Itara][SPIKE][TCCL] activateRaw ENTER component=" + id
                    + " thread=" + currentThread.getName() + "(" + currentThread.getId() + ")"
                    + " tcclBefore=" + describeClassLoader(previousCl)
                    + " willSetTo=" + describeClassLoader(componentCl));
            if (componentCl != null) currentThread.setContextClassLoader(componentCl);
            try {
                ItaraActivator activator = activatorClass.getDeclaredConstructor().newInstance();
                Object instance = activator.activate(this);
                log.fine("[Itara] activated component=" + id + " class=" + instance.getClass().getSimpleName());
                log.info("[Itara][SPIKE][TCCL] activateRaw BEFORE-RESTORE component=" + id
                        + " thread=" + currentThread.getName() + "(" + currentThread.getId() + ")"
                        + " tcclNow=" + describeClassLoader(currentThread.getContextClassLoader()));
                return instance;
            } finally {
                currentThread.setContextClassLoader(previousCl);
                log.info("[Itara][SPIKE][TCCL] activateRaw EXIT component=" + id
                        + " thread=" + currentThread.getName() + "(" + currentThread.getId() + ")"
                        + " tcclRestoredTo=" + describeClassLoader(previousCl));
            }

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
        Thread currentThread = Thread.currentThread();
        log.info("[Itara][SPIKE][TCCL] decorate component=" + id
                + " thread=" + currentThread.getName() + "(" + currentThread.getId() + ")"
                + " tcclAtDecorateTime=" + describeClassLoader(currentThread.getContextClassLoader())
                + " definingProxyUnder=" + describeClassLoader(currentThread.getContextClassLoader())
                + " targetOwnClassLoader=" + describeClassLoader(classLoaders.get(id)));
        //TODO: with the classloader isolation, the local proxy can no longer be skipped, it is a must-have even if there are no observers
        if (ObserverRegistry.instance().size() > 0 && contractClass != null) {
            return ObservabilityDecorator.wrap(
                    raw, id, contractClass,
                    Thread.currentThread().getContextClassLoader(),
                    classLoaders.get(id));
        }
        return raw;
    }

    private String describeClassLoader(ClassLoader cl) {
        if (cl == null) return "null";
        for (Map.Entry<String, ClassLoader> entry : classLoaders.entrySet()) {
            if (entry.getValue() == cl) return entry.getKey() + "@" + System.identityHashCode(cl);
        }
        return cl.getClass().getSimpleName() + "@" + System.identityHashCode(cl) + "(unregistered/system)";
    }

    /**
     * Eagerly activates every local component registered in this JVM
     * slice. Called once, by ItaraMain, right after agent setup completes
     * — turns every dependency chain and cross-component connection into
     * something either fully working or a clear boot-time failure, rather
     * than something discovered lazily on whichever request happens to
     * arrive first.
     *
     * Fails fast: the first activation failure propagates immediately,
     * aborting the rest of the loop. A component that can't activate
     * means this JVM slice has no business staying up.
     */
    public void activateAllLocal() {
        for (String id : Set.copyOf(activators.keySet())) {
            log.info("[Itara] eagerly activating component=" + id);
            get(id, Object.class);
        }
    }
}
