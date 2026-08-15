package io.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Backs the proxy ComponentLookup.getSelf() returns, when called with
 * observIncomingCalls=false. Opens componentId's own ComponentScope fresh
 * around every single call — never held open permanently — invokes the
 * raw implementation directly, and fires no observability events at all.
 *
 * Takes the delegate instance directly, resolved once by getSelf() before
 * this is constructed — unlike ItaraLocalProxyHandler, which re-resolves
 * on every call to guard against an activation-ordering hazard that
 * exists only during ItaraAgent's own wiring loop. getSelf() is called
 * from a script's main(), strictly after premain() has fully completed;
 * that hazard doesn't exist here, so re-resolving on every call would
 * just be a wasted lookup.
 *
 * See ObservedSelfInvocationHandler for the observIncomingCalls=true case
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
