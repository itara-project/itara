package io.itara.agent;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ComponentScopeHandle;
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
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-owned inbound call pipeline. Implements DispatchHandler.
 *
 * The transport calls dispatch() with raw bytes and receives raw bytes back.
 * This class owns everything in between:
 *
 *   0. Open this node's ComponentScope — outermost, before anything else,
 *      so registry lookup, method resolution, and every step below can rely
 *      on it being active (restore-as-soon-as-possible, matching how inbound
 *      observability context is already restored first).
 *   1. registry lookup      ← outside all further scopes, fails fast
 *   2. method resolution    ← outside all further scopes, fails fast
 *   3. restoreInboundContext (inbound scope opened — fires onInboundContextReleased on close)
 *   4. deserialize args      ← within inbound context, measurable in future
 *   5. CALL_RECEIVED         ← Itara/component boundary (callee scope opened)
 *   6. component.invoke()    ← TCCL already set to the component's own
 *      classloader, as part of step 0 opening the ComponentScope
 *   7. callee scope.close()  → RETURN_SENT
 *   8. serialize result      ← within inbound context, measurable in future
 *   9. inbound scope.close() → onInboundContextReleased, context popped
 *  10. ComponentScopeHandle.close() — restores the previous scope and TCCL,
 *      last, matching step 0 being first
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

    private final String dispatchKey;
    private final String componentId;
    private final String transportId;
    private final ItaraSerializer serializer;
    private final ItaraSerializerConfig serializerConfig;
    private final ItaraRegistry registry;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;
    private final ComponentScope scope;

    public ItaraDispatcher(String dispatchKey,
                           String componentId,
                           String transportId,
                           ItaraSerializer serializer,
                           ItaraSerializerConfig serializerConfig,
                           ItaraRegistry registry,
                           ExchangePattern exchangePattern,
                           ComponentScope scope) {
        this.dispatchKey      = Objects.requireNonNull(dispatchKey,
                "[Itara] ItaraDispatcher requires a non-null dispatchKey for component '" + componentId + "'.");
        this.componentId      = componentId;
        this.transportId      = transportId;
        this.serializer       = serializer;
        this.serializerConfig = serializerConfig;
        this.registry         = registry;
        this.facade           = ObservabilityFacade.instance();
        this.exchangePattern  = exchangePattern;

        // Received, not built — one ComponentScope exists per node, created
        // once at agent startup (ItaraAgent). This dispatcher holds the same
        // reference every other dispatcher or proxy for this node holds.
        this.scope = Objects.requireNonNull(scope,
                "[Itara] ItaraDispatcher requires a non-null ComponentScope for component '" + componentId + "'.");
    }

    @Override
    public String getDispatchKey() {
        return dispatchKey;
    }

    @Override
    public byte[] dispatch(String methodName, byte[] requestBytes, Map<String, String> headers) throws Exception {

        // 0. Open this node's scope — outermost, so registry lookup, method
        //    resolution, and every step below can rely on it being active.
        try (ComponentScopeHandle scopeHandle = ComponentScopeHandle.open(this.scope)) {
            // 1. Registry lookup — outside all scopes, fails fast before any context is opened
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

            // 2. Method resolution — outside all scopes
            Method method = findMethod(instance.getClass(), methodName);
            if (method == null) {
                throw serialized(new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.TRANSPORT,
                                "MethodNotFoundException",
                                "Method '" + methodName + "' not found on component '" + componentId + "'"),
                        methodName);
            }

            // 3. Restore inbound context — wraps deserialization, invocation, and serialization
            try (ItaraScope inboundScope = facade.restoreInboundContext(headers, exchangePattern)) {

                // 4. Deserialize args — within inbound context
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

                // 5. CALL_RECEIVED — callee scope wraps component invocation only
                Object result = null;
                try (ItaraScope calleeScope = facade.fireCallReceived(componentId, methodName, transportId, exchangePattern)) {
                    log.fine("[Itara] dispatch component=" + componentId + " method=" + methodName
                            + " classLoader=" + scope.getClassLoader());
                    try {
                        // 6. Component invocation
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
                    }
                } // 7. calleeScope.close() → RETURN_SENT

                // 8. Serialize result — within inbound context, after RETURN_SENT
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
            } // 9. Close inbound context
        } // 10. scopeHandle.close() — TCCL and ComponentScope restored last
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
