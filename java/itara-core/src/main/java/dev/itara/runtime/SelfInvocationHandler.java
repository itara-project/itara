package dev.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Backs the proxy ComponentLookup.getSelf() returns, when called with
 * observeIncomingCalls=false. Opens componentId's own ComponentScope fresh
 * around every single call — never held open permanently — invokes the
 * raw implementation directly, and fires no observability events at all.
 *
 * <p>See ObservedSelfInvocationHandler for the observeIncomingCalls=true case
 * — a separate, independent class, not a variant of this one.
 */
class SelfInvocationHandler implements InvocationHandler {

    private final Object delegate;
    private final ComponentScope scope;

    SelfInvocationHandler(Object delegate, ComponentScope scope) {
        this.delegate = Objects.requireNonNull(delegate,
                "[Itara] SelfInvocationHandler requires a non-null delegate.");
        this.scope = Objects.requireNonNull(scope,
                "[Itara] SelfInvocationHandler requires a non-null ComponentScope.");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
        }

        try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() != null) {
                    throw e.getCause();
                }
                throw e;
            }
        }
    }
}
