package io.itara.agent;

import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObservabilityFacade;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps a component instance in an observability decorator.
 *
 * Uses java.lang.reflect.Proxy — works because component contracts
 * are interfaces annotated with @ComponentInterface.
 *
 * Context management is fully delegated to ObservabilityFacade.
 * The decorator captures the previous context before firing CALL_SENT
 * so it can be restored after the call completes. It never creates
 * contexts directly.
 *
 * Transport type reported as "direct" for all calls through this decorator.
 */
public class ObservabilityDecorator {

    private static final String TRANSPORT = "direct";

    @SuppressWarnings("unchecked")
    public static Object wrap(Object instance,
                              String componentId,
                              Class<?> contractClass,
                              ClassLoader classLoader) {
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{ contractClass },
                new ObservabilityHandler(instance, componentId)
        );
    }

    private static class ObservabilityHandler implements InvocationHandler {

        private final Object delegate;
        private final String componentId;

        ObservabilityHandler(Object delegate, String componentId) {
            this.delegate    = delegate;
            this.componentId = componentId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {

            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            ObservabilityFacade facade = ObservabilityFacade.instance();

            ItaraContext previousCtx = ItaraContext.current();

            // CALL_SENT — caller side, resolves/creates context
            ItaraContext callerCtx = facade.fireCallSent(
                    componentId, method.getName(), TRANSPORT);

            // CALL_RECEIVED — callee side, creates child context
            // For direct calls both sides are in the same JVM so we fire
            // all four events. The child context gives the callee a distinct spanId.
            ItaraContext calleeCtx = facade.fireCallReceived(
                    callerCtx, componentId, method.getName(), TRANSPORT);

            boolean error = false;
            try {
                return method.invoke(delegate, args);

            } catch (Throwable t) {
                error = true;
                if (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
                    throw ite.getCause();
                }
                throw t;

            } finally {
                // RETURN_SENT — callee side
                facade.fireReturnSent(calleeCtx, componentId, method.getName(), error);

                // RETURN_RECEIVED — caller side, restores previous context
                facade.fireReturnReceived(callerCtx, previousCtx, componentId, method.getName(), error);
            }
        }
    }
}
