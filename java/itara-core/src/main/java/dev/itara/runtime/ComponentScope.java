package dev.itara.runtime;

import java.util.Objects;

/**
 * Identifies which component is currently executing on this thread.
 *
 * <p>A ComponentScope is created once per node, at the point that node starts
 * executing, and is never replaced — its reference stays constant for the
 * lifetime of the node. It is immutable: there are no setters, and
 * deliberately no equals()/hashCode() override. Identity is reference
 * identity only, matching ADR 0021 — two ComponentScope instances are
 * never meant to be compared for equivalence, only held onto or not.
 *
 * <p>Carries, at minimum:
 * <ul>
 * <li>nodeId — the topology node currently in control (see SPEC.md §3.6)</li>
 * <li>componentId — the component that node is an instance of</li>
 * <li>classLoader — the thread context classloader this node's code
 * should run under (see ItaraClassLoader)</li>
 * </ul>
 *
 * <p>This list is expected to grow. Construction goes through the nested
 * Factory — never a public constructor — so that adding a field never
 * requires a new constructor overload or breaks an existing call site.
 *
 * <p>The currently-active scope is held in an InheritableThreadLocal, not a
 * plain ThreadLocal. This is deliberate: a component that creates its own
 * thread pool must have that pool inherit its scope automatically, with no
 * extra code at the component's own call site (see component-scope-design.md,
 * "Thread pools and inheritance"). InheritableThreadLocal copies the
 * reference at thread-creation time only — safe here because the scope
 * object itself never changes after that.
 *
 * <p>There is no explicit stack (ADR 0022) — only the single, currently-active
 * scope. Nesting correctness comes from ComponentScopeHandle pairing every
 * open with a close at the same call site, the same way the call stack
 * already nests.
 *
 * <p>Setting the active scope is restricted to this package — only
 * ComponentScopeHandle may do it. Component code, and everything outside
 * dev.itara.runtime, may only read the active scope via current().
 */
public final class ComponentScope {

    private static final InheritableThreadLocal<ComponentScope> CURRENT = new InheritableThreadLocal<>();

    private final String nodeId;
    private final String componentId;
    private final ClassLoader classLoader;

    private ComponentScope(String nodeId, String componentId, ClassLoader classLoader) {
        this.nodeId      = nodeId;
        this.componentId = componentId;
        this.classLoader = classLoader;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /** @return the topology node currently in control on this thread */
    public String getNodeId()          { return nodeId; }
    /** @return the component the node is an instance of */
    public String getComponentId()     { return componentId; }
    /** @return the thread context classloader this node's code should run under */
    public ClassLoader getClassLoader() { return classLoader; }

    // ── Active-scope access ───────────────────────────────────────────────

    /**
     * Returns the scope currently active on this thread, or null if none is
     * — either because no crossing has happened yet on this thread, or
     * because this thread inherited nothing (was not created by
     * component-owned code under an active scope).
     */
    public static ComponentScope current() {
        return CURRENT.get();
    }

    /**
     * Sets the active scope for this thread. Package-private — only
     * ComponentScopeHandle may call this, as part of open()/close().
     */
    static void set(ComponentScope scope) {
        CURRENT.set(scope);
    }

    /**
     * Visible for testing only. Clears the active scope on this thread,
     * guarding against state leaking between tests on a reused thread.
     * Not part of normal call paths — normal paths always pair a set with
     * a restore via ComponentScopeHandle.
     */
    public static void resetForTest() {
        CURRENT.remove();
    }

    @Override
    public String toString() {
        return "ComponentScope{nodeId='" + nodeId + "', componentId='" + componentId
                + "', classLoader=" + classLoader + "}";
    }

    // ── Construction ─────────────────────────────────────────────────────

    /**
     * Builds a ComponentScope. All three fields are required — in
     * particular, classLoader is never allowed to be null: every thread in
     * a running JVM has a context classloader, so a null value here means
     * something upstream in the agent failed to resolve one, not that this
     * node legitimately has none.
     */
    public static final class Factory {

        private String nodeId;
        private String componentId;
        private ClassLoader classLoader;

        /** @param nodeId the topology node currently in control */
        public Factory nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /** @param componentId the component the node is an instance of */
        public Factory componentId(String componentId) {
            this.componentId = componentId;
            return this;
        }

        /** @param classLoader the thread context classloader this node's code should run under */
        public Factory classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        /**
         * @return the built {@link ComponentScope}
         * @throws NullPointerException if any of nodeId, componentId, or
         *         classLoader was not set
         */
        public ComponentScope build() {
            Objects.requireNonNull(nodeId, "[Itara] ComponentScope requires a non-null nodeId.");
            Objects.requireNonNull(componentId, "[Itara] ComponentScope requires a non-null componentId.");
            Objects.requireNonNull(classLoader,
                    "[Itara] ComponentScope requires a non-null classLoader — every thread has a "
                            + "context classloader, so a null value here indicates an agent bug, "
                            + "not a legitimate absence.");
            return new ComponentScope(nodeId, componentId, classLoader);
        }
    }
}
