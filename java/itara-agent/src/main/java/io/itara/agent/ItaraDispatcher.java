package io.itara.agent;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-owned inbound call pipeline. Implements DispatchHandler.
 *
 * The transport calls dispatch() with raw bytes and receives raw bytes back.
 * This class owns everything in between:
 *
 *   1. restoreInboundContext (inbound scope opened — fires onInboundContextReleased on close)
 *   2. deserialize args      ← within inbound context, measurable in future
 *   3. CALL_RECEIVED         ← Itara/component boundary (callee scope opened)
 *   4. set TCCL to the component's own classloader
 *   5. component.invoke()
 *   6. restore TCCL to its previous value (finally — runs on every exit path,
 *      including exceptions)
 *   7. callee scope.close()  → RETURN_SENT
 *   8. serialize result      ← within inbound context, measurable in future
 *   9. inbound scope.close() → onInboundContextReleased, context popped
 *
 * The callee scope wraps component invocation, and the TCCL swap around it —
 * setting and restoring TCCL is not itself observable overhead worth its own
 * span, but the invocation it wraps is exactly what the callee scope already
 * measures. Deserialization and serialization are within the inbound scope
 * but outside the callee scope — their cost is visible as transport
 * overhead and available for future measurement as dedicated spans.
 *
 * Constructed once per inbound connection at startup. All dependencies are
 * wired in, including the component's classloader (fetched once from the
 * registry at construction) — nothing is looked up at call time.
 */
public class ItaraDispatcher implements DispatchHandler {

    private static final Logger log = Logger.getLogger(ItaraDispatcher.class.getName());

    private final String componentId;
    private final String transportId;
    private final ItaraSerializer serializer;
    private final ItaraSerializerConfig serializerConfig;
    private final ItaraRegistry registry;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;
    private final ClassLoader componentClassLoader;

    public ItaraDispatcher(String componentId,
                           String transportId,
                           ItaraSerializer serializer,
                           ItaraSerializerConfig serializerConfig,
                           ItaraRegistry registry,
                           ExchangePattern exchangePattern) {
        this.componentId      = componentId;
        this.transportId      = transportId;
        this.serializer       = serializer;
        this.serializerConfig = serializerConfig;
        this.registry         = registry;
        this.facade           = ObservabilityFacade.instance();
        this.exchangePattern  = exchangePattern;

        // Fetched once, here, rather than on every dispatch() call — the
        // component's classloader never changes after activation, and this
        // dispatcher instance always serves exactly this one component.
        this.componentClassLoader = registry.getComponentClassLoader(componentId);
        if (this.componentClassLoader == null) {
            throw new IllegalStateException(
                    "[Itara] No classloader registered for component '" + componentId
                            + "' — its activator must be registered before wiring a dispatcher for it.");
        }
    }

    @Override
    public byte[] dispatch(String componentId, String methodName, byte[] requestBytes, Map<String, String> headers) throws Exception {

        // Registry lookup — outside all scopes, fails fast before any context is opened
        Object instance;
        try {
            instance = registry.getRawImplementation(componentId, Object.class);
        } catch (Exception e) {
            log.log(Level.SEVERE, "[Itara] registry lookup failed component=" + componentId
                    + " error=" + e.getMessage(), e);
            throw serialized(new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    e.getClass().getName(),
                    "Registry failure for component '" + componentId + "': " + e.getMessage(), e),
                    methodName);
        }

        // Method resolution — outside all scopes
        Method method = findMethod(instance.getClass(), methodName);
        if (method == null) {
            throw serialized(new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    "MethodNotFoundException",
                    "Method '" + methodName + "' not found on component '" + componentId + "'"),
                    methodName);
        }

        // 1. Restore inbound context — wraps deserialization, invocation, and serialization
        try (ItaraScope inboundScope = facade.restoreInboundContext(headers, exchangePattern)) {

            // 2. Deserialize args — within inbound context
            Object[] args;
            try {
                args = serializer.deserializeArgs(requestBytes, method.getParameterTypes(), serializerConfig);
            } catch (Exception e) {
                throw serialized(new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to deserialize arguments for '" + methodName
                                + "' on '" + componentId + "': " + e.getMessage(), e),
                        methodName);
            }

            // 3. CALL_RECEIVED — callee scope wraps component invocation only
            Object result = null;
            try (ItaraScope calleeScope = facade.fireCallReceived(componentId, methodName, transportId, exchangePattern)) {
                Thread currentThread = Thread.currentThread();
                ClassLoader previousCl = currentThread.getContextClassLoader();
                log.fine("[Itara] dispatch component=" + componentId + " method=" + methodName
                        + " classLoader=" + componentClassLoader);
                currentThread.setContextClassLoader(componentClassLoader);
                try {
                    // 4. Component invocation
                    result = method.invoke(instance, args);
                } catch (InvocationTargetException e) {
                    calleeScope.setError(true);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    // Rethrow as ItaraRemoteException — the transport will map this to an error response
                    throw serialized(new ItaraRemoteException(
                            cause instanceof RuntimeException || cause instanceof Error
                                    ? ItaraRemoteException.ErrorKind.RUNTIME
                                    : ItaraRemoteException.ErrorKind.CHECKED,
                            cause.getClass().getName(),
                            cause.getMessage(),
                            cause),
                            methodName);
                } catch (Exception e) {
                    calleeScope.setError(true);
                    throw e;
                } finally {
                    currentThread.setContextClassLoader(previousCl);
                }
            } // 5. calleeScope.close() → RETURN_SENT

            // 6. Serialize result — within inbound context, after RETURN_SENT
            try {
                return serializer.serializeResult(result, serializerConfig);
            } catch (Exception e) {
                throw serialized(new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to serialize result for '" + methodName
                                + "' on '" + componentId + "': " + e.getMessage(), e),
                        methodName);
            }
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /**
     * Attaches a serialized error payload to an ItaraRemoteException before throwing.
     * Every exception that leaves the dispatcher carries a payload — the transport
     * server writes it back as-is, and the proxy deserializes it. No empty bodies.
     */
    private ItaraRemoteException serialized(ItaraRemoteException ex, String methodName) {
        try {
            ex.withSerializedPayload(serializer.serializeResult(ex.toPayload(), serializerConfig));
        } catch (Exception e) {
            log.log(Level.WARNING, "[Itara] failed to serialize error payload component=" + componentId
                    + " method=" + methodName + " — caller will receive empty error body", e);
        }
        return ex;
    }
}
