package dev.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The narrow, component-facing entry point to the registry — the one
 * thing component code, activators, and standalone entry points need.
 *
 * <p>get(targetIdentifier, type) — resolve a dependency by its target
 * identifier (a component id, or an event-contract id for a connection
 * through a virtual node). Delegates to ItaraRegistry.instance().get() —
 * same requirements apply: an active ComponentScope, and a declared
 * outbound connection to targetIdentifier.
 *
 * <p>getSelf(componentId, type) — for standalone entry-point code (a bespoke
 * main(), a one-off script) only. See its own javadoc.
 */
// TODO(modularization): this belongs in a dedicated, publicly-exported
// package once the project modularizes (separate, tracked task) —
// dev.itara.api is for interfaces component authors implement, not this.
// Left in dev.itara.runtime for now since most of the package structure
// gets revisited at that point anyway. ItaraRegistry itself is not yet
// restricted — this class doesn't enforce anything by itself today, it's
// the seam modularization will use to actually hide ItaraRegistry and
// export only this.
public final class ComponentLookup {

    private static final AtomicBoolean CLAIMED = new AtomicBoolean(false);

    private ComponentLookup() {
    }

    /**
     * Resolves a dependency by its target identifier — a component id, or
     * an event-contract id for a connection through a virtual node.
     *
     * <p>This always returns a proxy for the connection declared for
     * {@code targetIdentifier} in the wiring config, never the
     * dependency's raw instance directly. Delegates to {@link
     * ItaraRegistry#get}; requires an active {@link ComponentScope} on the
     * calling thread and a declared outbound connection to
     * {@code targetIdentifier}.
     *
     * @param targetIdentifier the component id, or event-contract id, to
     *                         resolve a connection to
     * @param type             the interface type to return the proxy as
     * @param <T>              the interface type to return the proxy as
     * @return a proxy for the resolved connection
     */
    public static <T> T get(String targetIdentifier, Class<T> type) {
        return ItaraRegistry.instance().get(targetIdentifier, type);
    }

    /**
     * Claims identity as componentId, exactly once per JVM, and returns a
     * proxy to that component's own already-activated instance.
     *
     * <p>The returned proxy is safe to call any number of times, from any
     * thread, for the life of the process — each call opens componentId's
     * own ComponentScope fresh, around just that call.
     *
     * <p>For standalone entry-point code only (a bespoke main(), a one-off
     * script) — never from an activator or from inside any other crossing.
     *
     * @param componentId the identity to claim — must be a local
     *                    component's id, per this JVM slice's wiring
     * @param type        the interface type to return the proxy as
     * @param <T>         the interface type to return the proxy as
     * @param observeIncomingCalls whether CALL_RECEIVED/RETURN_SENT fire for
     *        each call made into the returned proxy
     * @return a proxy to this component's own activated instance
     * @throws IllegalStateException if a ComponentScope is already active
     *         on the calling thread, or if getSelf() has already succeeded
     *         once anywhere in this JVM
     */
    @SuppressWarnings("unchecked")
    public static <T> T getSelf(String componentId, Class<T> type, boolean observeIncomingCalls) {
        if (ComponentScope.current() != null) {
            throw new IllegalStateException(
                    "[Itara] Cannot claim identity as '" + componentId + "' — a ComponentScope is already "
                            + "active on this thread. getSelf() is for standalone entry-point code only — "
                            + "never from inside an activator or any other code already running inside a real "
                            + "crossing.");
        }
        if (!CLAIMED.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "[Itara] Cannot claim identity as '" + componentId + "' — getSelf() has already succeeded "
                            + "once in this JVM. It is a one-time, process-wide action, not a per-thread one — "
                            + "see getSelf()'s own javadoc for why.");
        }

        ComponentScope scope = ItaraRegistry.instance().getComponentScope(componentId);

        // If this is the first resolution of componentId, activation happens
        // here — under componentId's own scope, opened temporarily, so an
        // activator that itself calls ComponentLookup.get() has a scope to
        // call it from. Every other caller of getRawImplementation()
        // (ItaraDispatcher, ItaraLocalProxyHandler) already opens scope
        // first, for the same reason.
        Object delegate;
        try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
            delegate = ItaraRegistry.instance().getRawImplementation(componentId, Object.class);
        }

        InvocationHandler handler = observeIncomingCalls
                ? new ObservedSelfInvocationHandler(componentId, delegate, scope)
                : new SelfInvocationHandler(delegate, scope);

        return (T) Proxy.newProxyInstance(
                scope.getClassLoader(), new Class<?>[]{ type }, handler);
    }

    /**
     * Called by ItaraMain only, as the very first thing it does. Burns the
     * one-shot latch without opening any scope at all — ItaraMain hosts
     * local components but represents no single identity of its own to
     * claim, and must not fail startup over a lookup that was never
     * meaningful in the first place.
     *
     * <p>Package-private: nothing outside dev.itara.runtime should be
     * disabling this for anyone else.
     */
    static void disable() {
        CLAIMED.set(true);
    }

    /**
     * Visible for testing only.
     */
    public static void resetForTest() {
        CLAIMED.set(false);
    }
}
