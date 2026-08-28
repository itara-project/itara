package dev.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Backs the proxy ComponentLookup.getSelf() returns, when called with
 * observeIncomingCalls=true. Opens componentId's own ComponentScope fresh
 * around every single call — never held open permanently — then fires
 * exactly the two events that are honest for a self-driven call:
 * CALL_RECEIVED and RETURN_SENT. This component genuinely did receive an
 * invocation and genuinely did return a result; there is no CALL_SENT/
 * RETURN_RECEIVED pair to fire, because there is no other Itara-tracked
 * component on the other end of this call to have sent it or to receive
 * the reply.
 *
 * <p>A separate, independent class from SelfInvocationHandler — not a
 * variant or a decorator of it. Scope must open before CALL_RECEIVED
 * fires, matching ItaraDispatcher's own restore-as-soon-as-possible rule,
 * so that ComponentScope.current() is already correct for the whole
 * duration of any observability event.
 */
class ObservedSelfInvocationHandler implements InvocationHandler {

    private final String componentId;
    private final Object delegate;
    private final ComponentScope scope;
    private final ObservabilityFacade facade;

    ObservedSelfInvocationHandler(String componentId, Object delegate, ComponentScope scope) {
        this.componentId = Objects.requireNonNull(componentId,
                "[Itara] ObservedSelfInvocationHandler requires a non-null componentId.");
        this.delegate = Objects.requireNonNull(delegate,
                "[Itara] ObservedSelfInvocationHandler requires a non-null delegate.");
        this.scope = Objects.requireNonNull(scope,
                "[Itara] ObservedSelfInvocationHandler requires a non-null ComponentScope.");
        this.facade = ObservabilityFacade.instance();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
        }

        try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
            try (ItaraScope calleeScope = facade.fireCallReceived(
                    componentId, method.getName(), "direct", ExchangePattern.REQUEST_REPLY)) {
                try {
                    return method.invoke(delegate, args);
                } catch (Throwable t) {
                    calleeScope.setError(true);
                    if (t instanceof InvocationTargetException && t.getCause() != null) {
                        throw t.getCause();
                    }
                    throw t;
                }
            } // calleeScope.close() → RETURN_SENT
        }
    }
}
