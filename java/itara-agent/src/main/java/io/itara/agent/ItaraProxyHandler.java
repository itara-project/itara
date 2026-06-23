package io.itara.agent;

import io.itara.agent.metadata.MetadataFile;
import io.itara.exceptions.ItaraErrorPayload;
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
import java.util.Set;

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

    private final String componentId;
    private final String transportType;
    private final ItaraSerializer serializer;
    private final ItaraTransport transport;
    private final Map<String, String> properties;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;
    private final ItaraFailureSemantics failureSemantics;
    private final Set<String> nonIdempotentMethods;

    public ItaraProxyHandler(String componentId,
                             ItaraSerializer serializer,
                             ItaraTransport transport,
                             Map<String, String> properties,
                             ExchangePattern exchangePattern,
                             ItaraFailureSemantics failureSemantics,
                             MetadataFile apiMetadata) {
        this.componentId     = componentId;
        this.transportType   = transport.type();
        this.serializer      = serializer;
        this.transport       = transport;
        this.properties      = properties;
        this.facade          = ObservabilityFacade.instance();
        this.exchangePattern = exchangePattern;
        this.failureSemantics   = failureSemantics;
        this.nonIdempotentMethods = apiMetadata != null
                ? apiMetadata.getMethods().nonIdempotentSet()
                : Collections.emptySet();
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
                    // If there is a serialized payload, this exception came from the remote
                    // side — deserialize it to recover the real ErrorKind and message.
                    // CHECKED errors must not be retried and are rethrown immediately.
                    // RUNTIME errors are also rethrown — they are not infrastructure failures.
                    // Only locally-originated exceptions (null payload, kind TRANSPORT)
                    // should surface to the failure semantics layer for retry decisions.
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
}
