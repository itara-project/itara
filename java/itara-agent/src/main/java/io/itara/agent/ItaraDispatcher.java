package io.itara.agent;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.ItaraSerializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-owned inbound call pipeline. Implements DispatchHandler.
 *
 * The transport calls dispatch() with raw bytes and receives raw bytes back.
 * This class owns everything in between:
 *
 *   1. deserialize args
 *   2. registry lookup
 *   3. CALL_RECEIVED        ← Itara/component boundary
 *   4. component.invoke()
 *   5. RETURN_SENT          ← Itara/component boundary
 *   6. serialize result
 *
 * Observability events wrap the component invocation only (steps 3–5).
 * Deserialization and serialization are outside the span — their cost is
 * visible as transport overhead in the trace.
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

    public ItaraDispatcher(String componentId,
                           String transportType,
                           ItaraSerializer serializer,
                           ItaraRegistry registry) {
        this.componentId   = componentId;
        this.transportType = transportType;
        this.serializer    = serializer;
        this.registry      = registry;
        this.facade        = ObservabilityFacade.instance();
    }

    @Override
    public byte[] dispatch(String componentId, String methodName, byte[] requestBytes) throws Exception {

        // Registry lookup — infrastructure concern, outside the span
        // getRawImplementation() — dispatcher owns observability, must not receive
        // a decorated instance or double-firing will occur.
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

        // Method resolution — outside the span
        Method method = findMethod(instance.getClass(), methodName);
        if (method == null) {
            throw serialized(new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    "MethodNotFoundException",
                    "Method '" + methodName + "' not found on component '" + componentId + "'"));
        }

        // 1. Deserialize args — outside the span
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

        // 2. CALL_RECEIVED — Itara/component boundary, opens span
        //    incomingCtx is set on ThreadLocal by the transport before calling dispatch()
        ItaraContext incomingCtx = ItaraContext.current();
        ItaraContext callCtx = facade.fireCallReceived(incomingCtx, componentId, methodName, transportType);

        boolean error = false;
        Object result;
        try {
            // 3. Component invocation — inside the span
            result = method.invoke(instance, args);

        } catch (InvocationTargetException e) {
            error = true;
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
            error = true;
            throw e;
        } finally {
            // 4. RETURN_SENT — Itara/component boundary, closes span
            facade.fireReturnSent(callCtx, componentId, methodName, error);
        }

        // 5. Serialize result — outside the span
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
