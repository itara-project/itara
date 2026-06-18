package io.itara.agent;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.ItaraSerializer;

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
 *   4. component.invoke()
 *   5. callee scope.close()  → RETURN_SENT
 *   6. serialize result      ← within inbound context, measurable in future
 *   7. inbound scope.close() → onInboundContextReleased, context popped
 *
 * The callee scope wraps component invocation only. Deserialization and
 * serialization are within the inbound scope but outside the callee scope —
 * their cost is visible as transport overhead and available for future
 * measurement as dedicated spans.
 *
 * Constructed once per inbound connection at startup. All dependencies are
 * wired in — nothing is looked up at call time.
 */
public class ItaraDispatcher implements DispatchHandler {

    private static final Logger log = Logger.getLogger(ItaraDispatcher.class.getName());

    private final String componentId;
    private final String transportType;
    private final ItaraSerializer serializer;
    private final ItaraRegistry registry;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;

    public ItaraDispatcher(String componentId,
                           String transportType,
                           ItaraSerializer serializer,
                           ItaraRegistry registry,
                           ExchangePattern exchangePattern) {
        this.componentId     = componentId;
        this.transportType   = transportType;
        this.serializer      = serializer;
        this.registry        = registry;
        this.facade          = ObservabilityFacade.instance();
        this.exchangePattern = exchangePattern;
    }

    @Override
    public byte[] dispatch(String componentId, String methodName, byte[] requestBytes, Map<String, String> headers) throws Exception {

        // Registry lookup — outside all scopes, fails fast before any context is opened
        Object instance;
        try {
            instance = registry.getRawImplementation(componentId, Object.class);
        } catch (Exception e) {
            log.log(Level.SEVERE, "[Itara] Failed to retrieve component '"
                    + componentId + "' from registry.", e);
            throw serialized(new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    e.getClass().getName(),
                    "Registry failure for component '" + componentId + "': " + e.getMessage(), e));
        }

        // Method resolution — outside all scopes
        Method method = findMethod(instance.getClass(), methodName);
        if (method == null) {
            throw serialized(new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    "MethodNotFoundException",
                    "Method '" + methodName + "' not found on component '" + componentId + "'"));
        }

        // 1. Restore inbound context — wraps deserialization, invocation, and serialization
        try (ItaraScope inboundScope = facade.restoreInboundContext(headers, exchangePattern)) {

            // 2. Deserialize args — within inbound context
            Object[] args;
            try {
                args = serializer.deserializeArgs(requestBytes, method.getParameterTypes());
            } catch (Exception e) {
                throw serialized(new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to deserialize arguments for '" + methodName
                                + "' on '" + componentId + "': " + e.getMessage(), e));
            }

            // 3. CALL_RECEIVED — callee scope wraps component invocation only
            Object result = null;
            try (ItaraScope calleeScope = facade.fireCallReceived(componentId, methodName, transportType, exchangePattern)) {
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
                            cause));
                } catch (Exception e) {
                    calleeScope.setError(true);
                    throw e;
                }
            } // 5. calleeScope.close() → RETURN_SENT

            // 6. Serialize result — within inbound context, after RETURN_SENT
            try {
                return serializer.serializeResult(result);
            } catch (Exception e) {
                throw serialized(new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to serialize result for '" + methodName
                                + "' on '" + componentId + "': " + e.getMessage(), e));
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
    private ItaraRemoteException serialized(ItaraRemoteException ex) {
        try {
            ex.withSerializedPayload(serializer.serializeResult(ex.toPayload()));
        } catch (Exception e) {
            log.log(Level.WARNING, "[Itara] Failed to serialize error payload — "
                    + "caller will receive empty error body.", e);
        }
        return ex;
    }
}
