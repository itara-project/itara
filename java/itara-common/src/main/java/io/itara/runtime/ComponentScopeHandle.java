package io.itara.runtime;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * An AutoCloseable handle for a ComponentScope crossing (ADR 0022).
 *
 * open() captures whatever scope (and TCCL) was previously active on this
 * thread and sets the new scope in their place. close() restores both,
 * unconditionally. Always use in a try-with-resources block:
 *
 *   try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
 *       // component code runs here, under `scope`
 *   }
 *
 * There is no explicit stack. Because every crossing point pairs its own
 * open() with its own close() at the same call site, correct nesting comes
 * for free from the language's own call stack — a call three components
 * deep unwinds through three try-with-resources blocks closing in the
 * right order, automatically.
 *
 * A ComponentScopeHandle is deliberately much thinner than ItaraScope: no
 * setError(), no event firing. It exists purely to make restoration
 * structurally impossible to forget — it is not an observability construct.
 *
 * close() is safe to call more than once — it re-applies the same
 * previously-captured values each time — though normal use (try-with-
 * resources) never does this.
 *
 * ComponentScope itself stays immutable: this class holds the previous
 * state and does the mutating, so ComponentScope never needs a setter of
 * its own beyond the package-private one this class calls.
 */
public final class ComponentScopeHandle implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ComponentScopeHandle.class.getName());

    private final ComponentScope previousScope;
    private final ClassLoader previousClassLoader;

    private ComponentScopeHandle(ComponentScope previousScope, ClassLoader previousClassLoader) {
        this.previousScope       = previousScope;
        this.previousClassLoader = previousClassLoader;
    }

    /**
     * Opens a new scope on the current thread.
     *
     * Captures the thread's actual current context classloader directly
     * (not derived from the previous ComponentScope, which may be null) —
     * this way a thread with no prior scope but some ambient TCCL still
     * restores correctly on close.
     *
     * @param scope the scope to make active — must not be null
     */
    public static ComponentScopeHandle open(ComponentScope scope) {
        Objects.requireNonNull(scope, "[Itara] ComponentScopeHandle.open() requires a non-null scope.");

        Thread currentThread = Thread.currentThread();
        ComponentScope previousScope = ComponentScope.current();
        ClassLoader previousClassLoader = currentThread.getContextClassLoader();

        log.fine("[Itara] opening component scope node=" + scope.getNodeId()
                + " component=" + scope.getComponentId()
                + " classLoader=" + scope.getClassLoader());

        ComponentScope.set(scope);
        currentThread.setContextClassLoader(scope.getClassLoader());

        return new ComponentScopeHandle(previousScope, previousClassLoader);
    }

    /**
     * Restores the previously-active scope and classloader. Never throws.
     */
    @Override
    public void close() {
        Thread currentThread = Thread.currentThread();
        ComponentScope.set(previousScope);
        currentThread.setContextClassLoader(previousClassLoader);
        log.fine("[Itara] closed component scope, restored node="
                + (previousScope != null ? previousScope.getNodeId() : "null"));
    }
}
