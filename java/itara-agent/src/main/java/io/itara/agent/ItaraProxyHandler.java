package io.itara.agent;

import io.itara.agent.metadata.MetadataFile;
import io.itara.exceptions.ItaraErrorPayload;
import io.itara.exceptions.ItaraReconstructibleException;
import io.itara.exceptions.ItaraReconstructibleExceptionFactory;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.failuresemantics.TransportCall;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Generic InvocationHandler for all remote component calls, regardless of transport.
 *
 * Owns the complete outbound call pipeline:
 *   1. CALL_SENT  (scope opened — fires RETURN_RECEIVED on close)
 *   2. serialize args — once, outside the retry boundary; deterministic
 *   3. wrap transport call as a TransportCall lambda
 *   4. hand lambda to failure semantics — it decides attempt count and timeout
 *      - headers built per-attempt inside the lambda, so that any custom span
 *        opened by the failure semantics implementation for a retry attempt is
 *        the active context that gets propagated to the callee (§14.5, §14.7)
 *      - CHECKED errors deserialized and rethrown inside the lambda — not retriable
 *      - TRANSPORT errors surface to the failure semantics layer for retry decision
 *   5. deserialize result
 *   6. scope.close() → RETURN_RECEIVED
 *
 * The transport and failure semantics are slots filled at startup from the wiring
 * config. Switching either requires no change here.
 *
 * The idempotency flag passed to the failure semantics implementation is derived
 * at call time from the non-idempotent method set read from the API artifact's
 * .itara metadata at construction (§5.4, §14.6).
 *
 * This handler is the remote equivalent of ObservabilityDecorator — same
 * structural pattern, same agent ownership, same pipeline discipline.
 *
 * Uses java.lang.reflect.Proxy — works because component contracts are interfaces.
 */
public class ItaraProxyHandler implements InvocationHandler {

    private static final Logger log = Logger.getLogger(ItaraProxyHandler.class.getName());

    private final String componentId;
    private final String transportType;
    private final ItaraSerializer serializer;
    private final ItaraTransport transport;
    private final Map<String, String> properties;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;
    private final ItaraFailureSemantics failureSemantics;
    private final Set<String> nonIdempotentMethods;
    private final ItaraReconstructibleExceptionFactory exceptionFactory; // null if not registere

    public ItaraProxyHandler(String componentId,
                             ItaraSerializer serializer,
                             ItaraTransport transport,
                             Map<String, String> properties,
                             ExchangePattern exchangePattern,
                             ItaraFailureSemantics failureSemantics,
                             MetadataFile apiMetadata,
                             ItaraReconstructibleExceptionFactory exceptionFactory) {
        this.componentId          = componentId;
        this.transportType        = transport.type();
        this.serializer           = serializer;
        this.transport            = transport;
        this.properties           = properties;
        this.facade               = ObservabilityFacade.instance();
        this.exchangePattern      = exchangePattern;
        this.failureSemantics     = failureSemantics;
        this.nonIdempotentMethods = apiMetadata != null
                ? apiMetadata.getMethods().nonIdempotentSet()
                : Collections.emptySet();
        this.exceptionFactory = exceptionFactory;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        ItaraContext previousCtx = ItaraContext.current();

        // 1. CALL_SENT — scope.close() fires RETURN_RECEIVED
        try (ItaraScope scope = facade.fireCallSent(componentId, method.getName(), transportType, exchangePattern)) {

            // 2. Serialize args — once, outside the retry lambda.
            //    Serialization is deterministic; there is no value in repeating it.
            Object[] safeArgs = (args == null) ? new Object[0] : args;
            byte[] payload;
            try {
                payload = serializer.serializeArgs(safeArgs);
            } catch (Exception e) {
                scope.setError(true);
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to serialize arguments for '" + method.getName()
                                + "' on '" + componentId + "': " + e.getMessage(), e);
            }

            // 3. Transport — wrapped in a TransportCall lambda and handed to the
            //    failure semantics implementation. The implementation decides how
            //    many times to invoke it, with what timeout, and when to give up.
            //
            //    Headers are built inside the lambda, immediately before the transport
            //    call, on every attempt. This ensures that if the failure semantics
            //    implementation emits a custom span before a retry attempt, that span
            //    is the active context when headers are built and consequently what
            //    is propagated to the callee (§14.5, §14.7).
            boolean idempotent = !nonIdempotentMethods.contains(method.getName());

            TransportCall work = (timeout) -> {
                // Headers built per-attempt — active context at this point is what
                // gets propagated, including any retry span the failure semantics
                // implementation may have opened (§14.5)
                Map<String, String> headers = facade.buildOutboundHeaders();
                try {
                    return transport.send(componentId, method.getName(), payload, headers, properties, timeout);
                } catch (ItaraRemoteException e) {
                    // Deserialize and reconstruct any exception that carries a serialized
                    // payload — this recovers the real ErrorKind from the remote side.
                    // CHECKED errors are rethrown immediately and must not be retried.
                    // RUNTIME errors surface to the failure semantics layer, which decides
                    // whether to retry based on retryRuntime configuration.
                    if (e.getSerializedPayload() != null) {
                        try {
                            ItaraErrorPayload errorPayload = (ItaraErrorPayload) serializer.deserializeResult(
                                    e.getSerializedPayload(), ItaraErrorPayload.class);
                            throw ItaraRemoteException.from(errorPayload);
                        } catch (ItaraRemoteException re) {
                            throw re;
                        } catch (Exception deserEx) {
                            throw new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.TRANSPORT,
                                    deserEx.getClass().getName(),
                                    "Failed to deserialize error payload from '" + componentId
                                            + "': " + deserEx.getMessage(), deserEx);
                        }
                    }
                    // No serialized payload — locally-originated TRANSPORT failure.
                    // Surface to failure semantics for retry decision.
                    throw e;
                } catch (Exception e) {
                    throw new ItaraRemoteException(
                            ItaraRemoteException.ErrorKind.TRANSPORT,
                            e.getClass().getName(),
                            "Transport failure calling '" + componentId
                                    + "." + method.getName() + "': " + e.getMessage(), e);
                }
            };

            byte[] responseBytes;
            try {
                responseBytes = failureSemantics.execute(work, idempotent);
            } catch (ItaraRemoteException e) {
                scope.setError(true);
                if (e.getErrorKind() == ItaraRemoteException.ErrorKind.CHECKED
                        && exceptionFactory != null) {
                    Optional<ItaraReconstructibleException> reconstructed =
                            exceptionFactory.reconstruct(e.getRemoteExceptionClass(), e.getMessage());
                    if (reconstructed.isPresent()) {
                        if (reconstructed.get() instanceof Throwable
                                && isDeclaredOn(method, (Throwable) reconstructed.get())) {
                            throw (Throwable) reconstructed.get();
                        }
                        // Reconstruction produced a type not declared on this method, or a
                        // non-Throwable. Both are factory contract violations — log and fall back.
                        // Note: non-Throwable implementations of ItaraReconstructibleException
                        // will also be caught here; the Java compiler prevents throwing
                        // non-Throwables so this can only happen via a careless factory.
                        log.warning("[Itara] Exception factory for contract '" + componentId
                                + "' returned '"
                                + reconstructed.get().getClass().getName()
                                + "' for error '" + e.getRemoteExceptionClass()
                                + "' but it is not declared on method '" + method.getName()
                                + "' — falling back to ItaraRemoteException.");
                    }
                }
                throw e;
            }

            // 5. Deserialize result
            try {
                return serializer.deserializeResult(responseBytes, method.getReturnType());
            } catch (Exception e) {
                scope.setError(true);
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to deserialize response from '" + componentId
                                + "." + method.getName() + "': " + e.getMessage(), e);
            }

        } // 6. scope.close() → RETURN_RECEIVED, context popped
    }

    /**
     * Returns true if the given throwable is assignment-compatible with any
     * checked exception declared on the method. Subtype relationships are
     * handled correctly — a reconstructed subclass of a declared exception
     * type passes this check.
     *
     * Used to guard reconstruction: if the factory returns a type the method
     * doesn't declare, throwing it would cause the JDK proxy to wrap it in
     * UndeclaredThrowableException. We detect this and fall back instead.
     */
    private static boolean isDeclaredOn(Method method, Throwable t) {
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isAssignableFrom(t.getClass())) return true;
        }
        return false;
    }
}
