package io.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps a component instance in an observability decorator.
 *
 * Uses java.lang.reflect.Proxy — works because component contracts
 * are interfaces annotated with @ComponentInterface.
 *
 * Context management is fully delegated to ObservabilityFacade via scopes.
 * The caller scope (fireCallSent) wraps the callee scope (fireCallReceived),
 * mirroring the event ordering: CALL_SENT → CALL_RECEIVED → invoke →
 * RETURN_SENT → RETURN_RECEIVED. Both scopes fire their close events and
 * pop the ItaraContext stack on exit — no manual context handling here.
 *
 * Transport type reported as "direct" for all calls through this decorator.
 */
public class ObservabilityDecorator {

    private static final String TRANSPORT = "direct";

    @SuppressWarnings("unchecked")
    public static Object wrap(Object instance,
                              String componentId,
                              Class<?> contractClass,
                              ClassLoader classLoader,
                              ClassLoader targetClassLoader) {
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{ contractClass },
                new ObservabilityHandler(instance, componentId, targetClassLoader)
        );
    }

    private static class ObservabilityHandler implements InvocationHandler {

        private final Object delegate;
        private final String componentId;
        private final ClassLoader targetClassLoader;

        ObservabilityHandler(Object delegate, String componentId, ClassLoader targetClassLoader) {
            this.delegate           = delegate;
            this.componentId        = componentId;
            this.targetClassLoader  = targetClassLoader;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            ObservabilityFacade facade = ObservabilityFacade.instance();

            // CALL_SENT — caller scope; close fires RETURN_RECEIVED
            try (ItaraScope callerScope = facade.fireCallSent(componentId, method.getName(), TRANSPORT, ExchangePattern.REQUEST_REPLY)) {

                // CALL_RECEIVED — callee scope; close fires RETURN_SENT
                try (ItaraScope calleeScope = facade.fireCallReceived(componentId, method.getName(), TRANSPORT, ExchangePattern.REQUEST_REPLY)) {

                    Thread currentThread = Thread.currentThread();
                    ClassLoader previousCl = currentThread.getContextClassLoader();
                    java.util.logging.Logger.getLogger(ObservabilityDecorator.class.getName()).info(
                            "[Itara][SPIKE][TCCL] decoratorInvoke component=" + componentId
                                    + " method=" + method.getName()
                                    + " thread=" + currentThread.getName() + "(" + currentThread.getId() + ")"
                                    + " tcclBefore=" + previousCl
                                    + " tcclWillSetTo=" + targetClassLoader);
                    if (targetClassLoader != null) currentThread.setContextClassLoader(targetClassLoader);
                    try {
                        return method.invoke(delegate, args);
                    } catch (Throwable t) {
                        calleeScope.setError(true);
                        callerScope.setError(true);
                        if (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
                            throw ite.getCause();
                        }
                        throw t;
                    } finally {
                        currentThread.setContextClassLoader(previousCl);
                    }
                } // RETURN_SENT
            } // RETURN_RECEIVED
        }
    }
}
