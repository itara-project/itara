package dev.itara.runtime;

import dev.itara.api.ItaraActivator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The Itara component registry for this JVM slice.
 *
 * <p>The agent populates it before any application code runs:
 * <ul>
 * <li>Every connection: registerConnectionProxy() with its own proxy,
 * local (ItaraLocalProxyHandler) or remote (ItaraProxyHandler), and
 * registerOutboundConnection() so get() can find it from the calling
 * side</li>
 * <li>Local components: registerComponentScope() with that node's own
 * ComponentScope, and registerActivator() with the activator class</li>
 * </ul>
 *
 * <p>Two explicit retrieval methods reflect two fundamentally different use cases:
 * <ul>
 * <li>{@code get()} — for application code and activators. Resolves via
 * the caller's own active ComponentScope — see get()'s own javadoc.
 * Requires a scope; there is no fallback for an unscoped caller.</li>
 * <li>{@code getRawImplementation()} — for a proxy or dispatcher only.
 * Always returns the raw activated instance with no wrapping. Only ever
 * reachable from code we control, which already opened the correct scope
 * before calling this — so this method itself never needs to think about
 * scope at all.</li>
 * </ul>
 *
 * <p>Singleton — one registry per JVM, accessed via ItaraRegistry.instance().
 */
public class ItaraRegistry {

    private static final Logger log = Logger.getLogger(ItaraRegistry.class.getName());

    private static final ItaraRegistry INSTANCE = new ItaraRegistry();

    // Every connection's proxy (local and remote alike), keyed by the
    // connection's own id — see ItaraLocalProxyHandler, ItaraProxyHandler.
    // Populated by the agent for every declared connection. This is what
    // get() actually resolves through, once it has translated a target
    // identifier to a connection id via outboundConnections below.
    private final Map<String, Object> connectionProxies = new ConcurrentHashMap<>();

    // The index get() actually resolves through: fromNodeId -> (target
    // identifier -> connectionId). "Target identifier" is whatever a
    // connection's own contractIdentifier() resolves to — a component id
    // for a direct/remote connection, an event-contract id for a
    // publisher's connection to a virtual node — so this one index covers
    // both without special-casing either. Populated by the agent
    // alongside registerConnectionProxy(); throws on an ambiguous
    // registration (see registerOutboundConnection()).
    private final Map<String, Map<String, String>> outboundConnections = new ConcurrentHashMap<>();

    // Every local node's own ComponentScope, keyed by component id — a
    // node id would be equally valid as the key (see ComponentScope), but
    // component id is what ComponentLookup.getSelf() and every other
    // component-facing lookup already uses, and no two local nodes in one
    // deployment unit ever share a component id, so this is unambiguous.
    // Populated by the agent in the same pass that builds each scope;
    // read by ComponentLookup.getSelf() for standalone entry-point code.
    private final Map<String, ComponentScope> componentScopes = new ConcurrentHashMap<>();

    // Raw activated instances — served to the dispatcher only
    private final Map<String, Object> rawInstances = new ConcurrentHashMap<>();

    // Activator classes for local components, registered by the agent
    private final Map<String, Class<? extends ItaraActivator>> activators =
            new ConcurrentHashMap<>();

    // Tracks which component ids are currently being activated
    // to detect circular dependencies. Best-effort.
    private final Map<String, Thread> activating = new ConcurrentHashMap<>();

    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    private ItaraRegistry() {}

    /**
     * Returns the singleton registry instance for this JVM.
     *
     * @return the singleton registry instance for this JVM
     */
    public static ItaraRegistry instance() {
        return INSTANCE;
    }

    // ── Agent setup API ───────────────────────────────────────────────────────

    /**
     * Called by the agent to register a connection's own proxy — local or
     * remote alike — keyed by the connection's own id. Every declared
     * connection gets exactly one entry here (see WiringConfig's
     * connection-id uniqueness validation).
     *
     * <p>Throws if connectionId is already registered — WiringConfig.validate()
     * should already have rejected a duplicate id before the agent ever
     * gets here, so a collision at this point indicates a registry-level
     * bug, not a config error; this class should not silently trust that
     * upstream validation ran.
     *
     * @param connectionId the connection's own id
     * @param proxy        the connection's own proxy — local or remote alike
     */
    public void registerConnectionProxy(String connectionId, Object proxy) {
        Object existing = connectionProxies.putIfAbsent(connectionId, proxy);
        if (existing != null) {
            throw new IllegalStateException(
                    "[Itara] Duplicate connection proxy registration for connectionId='" + connectionId + "'.");
        }
        log.fine("[Itara] registered connection proxy connectionId=" + connectionId);
    }

    /**
     * Called by the agent to register that node fromNodeId has an outbound
     * connection to targetIdentifier, resolved via connectionId — the
     * index get() actually resolves through.
     *
     * <p>Throws if fromNodeId already has a different connection registered
     * for the same targetIdentifier — two outbound connections from one
     * node to two different nodes sharing a component id would be
     * genuinely ambiguous to a caller, which only ever supplies
     * targetIdentifier, never a node id (ADR 0023's reasoning, extended to
     * this index — the outbound-ambiguity rule decided a while back,
     * enforced here for the first time).
     *
     * @param fromNodeId       the local node with the outbound connection
     * @param targetIdentifier the component or event-contract id this
     *                         connection resolves to
     * @param connectionId     the connection's own id
     */
    public void registerOutboundConnection(String fromNodeId, String targetIdentifier, String connectionId) {
        Map<String, String> fromCaller = outboundConnections.computeIfAbsent(
                fromNodeId, key -> new ConcurrentHashMap<>());
        String existing = fromCaller.putIfAbsent(targetIdentifier, connectionId);
        if (existing != null) {
            throw new IllegalStateException(
                    "[Itara] Node '" + fromNodeId + "' already has an outbound connection '" + existing
                            + "' to '" + targetIdentifier + "' — cannot also register '" + connectionId
                            + "'. A node cannot have two outbound connections to different targets that "
                            + "share the same component id.");
        }
        log.fine("[Itara] registered outbound connection from=" + fromNodeId
                + " to=" + targetIdentifier + " connectionId=" + connectionId);
    }

    /**
     * Called by the agent to register a local node's own ComponentScope,
     * once, in the same pass that builds it — the reference this stores
     * must be the exact same object every dispatcher/proxy for that node
     * already holds (ADR 0021: one scope per node, ever).
     *
     * <p>Throws if componentId is already registered — no two local nodes in
     * one deployment unit share a component id, so a collision here means
     * this was called twice for the same component, not a legitimate
     * second node.
     *
     * @param componentId the local node's own component id
     * @param scope       the local node's own ComponentScope
     */
    public void registerComponentScope(String componentId, ComponentScope scope) {
        ComponentScope existing = componentScopes.putIfAbsent(componentId, scope);
        if (existing != null) {
            throw new IllegalStateException(
                    "[Itara] ComponentScope already registered for component '" + componentId + "'.");
        }
    }

    /**
     * Retrieve a local node's own ComponentScope by component id.
     *
     * <p>For {@code ComponentLookup.getSelf()} only — standalone entry-point
     * code declaring its own identity. Not for activators or any other
     * component-facing code, which reach a scope only implicitly, by
     * already running inside one.
     *
     * @param componentId the local node's own component id
     * @return the local node's own ComponentScope
     * @throws IllegalStateException if componentId has no registered scope
     *         — not a local component in this JVM slice.
     */
    public ComponentScope getComponentScope(String componentId) {
        ComponentScope scope = componentScopes.get(componentId);
        if (scope == null) {
            throw new IllegalStateException(
                    "[Itara] No ComponentScope registered for component '" + componentId
                            + "' — check that it's a local component in this JVM slice.");
        }
        return scope;
    }

    /**
     * Called by the agent to register how to activate a local component.
     * Activation is lazy — triggered on the first call to
     * getRawImplementation() for this component (including indirectly, via
     * getSelf()). This preserves Spring and framework compatibility: the
     * activator runs after the application context is ready, not during
     * premain.
     *
     * @param id             the component id being registered
     * @param activatorClass the activator class to instantiate on first activation
     */
    public void registerActivator(String id, Class<? extends ItaraActivator> activatorClass) {
        activators.put(id, activatorClass);
        log.fine("[Itara] registered activator component=" + id + " class=" + activatorClass.getName());
    }

    /**
     * Registers an alias so that lookups by aliasId delegate to canonicalId.
     * Used to map event contract ids to consumer component ids.
     * e.g. "order-events/order-placed" -> "order-consumer"
     *
     * @param aliasId     the id lookups will arrive under
     * @param canonicalId the id to actually resolve through
     */
    public void registerAlias(String aliasId, String canonicalId) {
        aliases.put(aliasId, canonicalId);
        log.fine("[Itara] registered alias id=" + aliasId + " canonical=" + canonicalId);
    }

    // ── Application API ───────────────────────────────────────────────────────

    /**
     * Retrieve a connection's own proxy by its connection id.
     *
     * <p>The mechanism get() actually resolves through, once it has found the
     * right connectionId via the caller's own ComponentScope (see get()).
     * Also usable directly, by connection id, for tests or anything else
     * that already knows exactly which connection it wants.
     *
     * @param connectionId the connection id to look up
     * @param type         the interface type to return the proxy as
     * @param <T>          the interface type to return the proxy as
     * @return the connection's own proxy
     * @throws IllegalStateException if no connection with this id was
     *         registered — a topology/wiring bug, not a runtime condition
     *         to recover from.
     */
    @SuppressWarnings("unchecked")
    public <T> T getConnectionProxy(String connectionId, Class<T> type) {
        Object proxy = connectionProxies.get(connectionId);
        if (proxy == null) {
            throw new IllegalStateException(
                    "[Itara] No connection proxy registered for connectionId='" + connectionId + "'.");
        }
        return type.cast(proxy);
    }

    /**
     * Retrieve a component for use by application code or activators.
     *
     * <p>Resolves via the caller's own active ComponentScope (ADR 0021) — the
     * scope's node id, plus the requested targetIdentifier, look up the
     * declared connection id via outboundConnections, which in turn
     * resolves to that connection's own pre-built proxy in connectionProxies.
     * Local or remote, direct or transport-based: the caller cannot tell
     * which, and does not need to — each connection's own proxy
     * (ItaraLocalProxyHandler or ItaraProxyHandler) already owns everything
     * call-specific, built once at startup.
     *
     * @param targetIdentifier the component id, or event-contract id, to
     *                         resolve a connection to
     * @param type             the interface type to return the proxy as
     * @param <T>              the interface type to return the proxy as
     * @return a proxy for the resolved connection
     * @throws IllegalStateException if no active ComponentScope, or if no
     *         connection is declared from the caller's own node to
     *         targetIdentifier — either a caller invoked from an unscoped
     *         thread, or a topology/wiring config error.
     */
    public <T> T get(String targetIdentifier, Class<T> type) {
        ComponentScope caller = ComponentScope.current();
        if (caller == null) {
            throw new IllegalStateException(
                    "[Itara] No active ComponentScope — get() can only be called from within a "
                            + "component's own execution (via a proxy or dispatcher), never from an "
                            + "unscoped thread.");
        }

        Map<String, String> fromCaller = outboundConnections.get(caller.getNodeId());
        String connectionId = fromCaller != null ? fromCaller.get(targetIdentifier) : null;
        if (connectionId == null) {
            throw new IllegalStateException(
                    "[Itara] No connection declared from node '" + caller.getNodeId()
                            + "' to '" + targetIdentifier + "' — check your wiring config.");
        }

        return getConnectionProxy(connectionId, type);
    }

    /**
     * Retrieve the raw implementation of a local component.
     *
     * <p>For use by a proxy or dispatcher only — code we control completely,
     * which has already opened the target's correct ComponentScope before
     * calling this (ItaraDispatcher.dispatch(), ItaraLocalProxyHandler.invoke()).
     * Never called directly by application/activator code, and never
     * decorated — a dispatcher or local proxy owns its own observability
     * pipeline; a decorated instance would cause double event firing.
     *
     * <p>computeIfAbsent guarantees a single instance even under concurrent
     * dispatch — all listeners share the same activated instance.
     *
     * @param id   the local component's id
     * @param type the interface type to return the instance as
     * @param <T>  the interface type to return the instance as
     * @return the raw activated instance
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

            ItaraActivator activator = activatorClass.getDeclaredConstructor().newInstance();

            Object instance = activator.activate();
            log.fine("[Itara] activated component=" + id + " class=" + instance.getClass().getSimpleName());
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

    // TODO(good-first-issue): activateAllLocal() gives boot-time
    // fail-fast activation (see ItaraMain). It calls get(), which now
    // requires an active ComponentScope. It needs replacement: a
    // registry-maintained list of every registered proxy and dispatcher,
    // each able to eager-activate/cache its own delegate where that makes
    // sense (see ItaraLocalProxyHandler).
    //
    // public void activateAllLocal() {
    //     for (String id : new TreeSet<>(activators.keySet())) {
    //         log.info("[Itara] eagerly activating component=" + id);
    //         get(id, Object.class);
    //     }
    // }

    /**
     * Eagerly activates every local component registered in this JVM slice.
     *
     * <p>Called once by {@link ItaraMain} at boot, for boot-time fail-fast
     * activation — so a misconfigured component fails startup immediately
     * rather than on its first real call. Not called at all when a host
     * application supplies its own entry point; in that case every local
     * component activates lazily, on first use, as normal.
     *
     * <p>For each local component, opens that component's own
     * {@link ComponentScope} and calls {@link #getRawImplementation}
     * under it — the same scope-then-activate sequence a dispatcher or
     * local proxy would use for a real call.
     */
    public void activateAllLocal() {
        for (Map.Entry<String, ComponentScope> componentAndScope : componentScopes.entrySet()) {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(componentAndScope.getValue())) {
                log.info("[Itara] eagerly activating component=" + componentAndScope.getKey());
                getRawImplementation(componentAndScope.getKey(), Object.class);
            }
        }
    }

    /**
     * Visible for testing — clears all registry state.
     */
    public void reset() {
        connectionProxies.clear();
        outboundConnections.clear();
        componentScopes.clear();
        rawInstances.clear();
        activators.clear();
        activating.clear();
        aliases.clear();
    }
}
