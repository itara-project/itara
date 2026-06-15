package io.itara.agent;

import io.itara.exceptions.ItaraErrorPayload;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Generic InvocationHandler for all remote component calls, regardless of transport.
 *
 * Owns the complete outbound call pipeline:
 *   1. CALL_SENT  (scope opened — fires RETURN_RECEIVED on close)
 *   2. build outbound headers
 *   3. serialize args
 *   4. transport.send() — pure byte carrier, knows nothing else
 *   5. deserialize result
 *   6. scope.close() → RETURN_RECEIVED
 *
 * The transport is a slot filled at startup from the wiring config.
 * Switching transports requires no change here.
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

    public ItaraProxyHandler(String componentId,
                             ItaraSerializer serializer,
                             ItaraTransport transport,
                             Map<String, String> properties) {
        this.componentId   = componentId;
        this.transportType = transport.type();
        this.serializer    = serializer;
        this.transport     = transport;
        this.properties    = properties;
        this.facade        = ObservabilityFacade.instance();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        ItaraContext previousCtx = ItaraContext.current();

        // 1. CALL_SENT — scope.close() fires RETURN_RECEIVED
        try (ItaraScope scope = facade.fireCallSent(componentId, method.getName(), transportType)) {

            // 2. Build outbound headers — Itara-native + per-observer (e.g. OTel W3C)
            Map<String, String> headers = facade.buildOutboundHeaders();

            // 3. Serialize args
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

            // 4. Transport — bytes in, bytes out
            byte[] responseBytes;
            try {
                responseBytes = transport.send(componentId, method.getName(), payload, headers, properties);
            } catch (ItaraRemoteException e) {
                scope.setError(true);
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
            } catch (Exception e) {
                scope.setError(true);
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Transport failure calling '" + componentId
                                + "." + method.getName() + "': " + e.getMessage(), e);
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
