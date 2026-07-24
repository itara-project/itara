package io.itara.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

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

    private static final Logger log = Logger.getLogger(ObservabilityDecorator.class.getName());

    private static final String TRANSPORT = "direct";

    @SuppressWarnings("unchecked")
    public static Object wrap(Object instance,
                              String componentId,
                              Class<?> contractClass,
                              ClassLoader componentClassLoader) {
        return Proxy.newProxyInstance(
                componentClassLoader,
                new Class<?>[]{ contractClass },
                new ObservabilityHandler(instance, componentId, componentClassLoader)
        );
    }

    private static class ObservabilityHandler implements InvocationHandler {

        private final Object delegate;
        private final String componentId;
        private final ClassLoader componentClassLoader;

        ObservabilityHandler(Object delegate, String componentId, ClassLoader componentClassLoader) {
            this.delegate             = delegate;
            this.componentId          = componentId;
            this.componentClassLoader = componentClassLoader;
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
                    ClassLoader previousClassLoader = currentThread.getContextClassLoader();
                    log.fine("[Itara] direct call component=" + componentId + " method=" + method.getName()
                            + " classLoader=" + componentClassLoader);
                    currentThread.setContextClassLoader(componentClassLoader);
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
                        currentThread.setContextClassLoader(previousClassLoader);
                    }
                } // RETURN_SENT
            } // RETURN_RECEIVED
        }
    }
}
