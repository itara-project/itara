package io.itara.agent;

import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObserverRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps a component instance in an observability decorator.
 *
 * Uses java.lang.reflect.Proxy — works because component contracts
 * are interfaces annotated with @ComponentInterface.
 *
 * For direct (colocated) calls, all four events fire in sequence
 * within the same JVM. The time between CALL_SENT and CALL_RECEIVED,
 * and between RETURN_SENT and RETURN_RECEIVED, is effectively zero —
 * this is the observable proof that colocation is functioning.
 *
 * Called by the agent's ComponentFactory at activation time.
 * The registry stores and returns the decorator transparently.
 * The real instance is held as the delegate — never exposed directly.
 */
public class ObservabilityDecorator {

    /**
     * Wraps a component instance in an observability decorator.
     *
     * @param instance      the real component instance produced by the activator
     * @param componentId   the component identifier from the wiring config
     * @param contractClass the @ComponentInterface interface
     * @param classLoader   the classloader to define the proxy class in
     * @return              a proxy implementing contractClass that fires all four events
     */
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

    // ── InvocationHandler ──────────────────────────────────────────────────

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

            // Pass through Object methods directly — no events for equals,
            // hashCode, toString etc.
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            // If no context exists on this thread, this is an entry point call
            // (e.g. called directly from main or a framework controller).
            // Create a root context and clear it in finally since we own it.
            ItaraContext ctx = ItaraContext.current();
            boolean isRootCaller = (ctx == null);
            if (isRootCaller) {
                ctx = ItaraContext.newRoot(componentId);
                ItaraContext.set(ctx);
            }

            // Create a child span for the callee side — even for direct calls
            // the caller and callee must have distinct spanIds so traces can
            // distinguish the two sides and calculate per-side timing correctly.
            // The parent context is restored in finally.
            ItaraContext calleeCtx = ctx.newChildSpan(componentId);
            ItaraContext.set(calleeCtx);

            ObserverRegistry observers = ObserverRegistry.instance();
            boolean error = false;

            // ── CALL_SENT ──────────────────────────────────────────────────
            // Fired with the caller's context (ctx) — caller side event
            observers.fireCallSent(ctx, componentId, method.getName());

            // ── CALL_RECEIVED ──────────────────────────────────────────────
            // Fired with the callee's context (calleeCtx) — callee side event
            observers.fireCallReceived(calleeCtx, componentId, method.getName());

            try {
                Object result = method.invoke(delegate, args);
                return result;

            } catch (Throwable t) {
                error = true;
                // Unwrap InvocationTargetException so the caller sees
                // the original exception, not a reflection wrapper
                if (t instanceof java.lang.reflect.InvocationTargetException ite
                        && ite.getCause() != null) {
                    throw ite.getCause();
                }
                throw t;

            } finally {
                // ── RETURN_SENT ────────────────────────────────────────────
                // Fired with callee's context — callee side event
                observers.fireReturnSent(calleeCtx, componentId, method.getName(), error);

                // ── RETURN_RECEIVED ────────────────────────────────────────
                // Fired with caller's context — caller side event
                observers.fireReturnReceived(ctx, componentId, method.getName(), error);

                // Restore caller's context — or clear if we created the root
                if (isRootCaller) {
                    ItaraContext.clear();
                } else {
                    ItaraContext.set(ctx);
                }
            }
        }
    }
}
