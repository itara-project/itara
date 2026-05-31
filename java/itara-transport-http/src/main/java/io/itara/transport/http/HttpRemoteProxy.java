package io.itara.transport.http;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.spi.ItaraSerializer;

import io.itara.runtime.ContextPropagation;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObservabilityFacade;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

/**
 * InvocationHandler for remote component calls over HTTP.
 *
 * Fires onCallSent before the call and onReturnReceived after.
 * Propagates ItaraContext to the remote side via W3C Trace Context headers.
 * Always clears context in finally — never leaks between requests.
 */
public class HttpRemoteProxy implements InvocationHandler {

    private static final Logger log = Logger.getLogger(HttpRemoteProxy.class.getName());

    private final String componentId;
    private final String host;
    private final int port;
    private final ItaraSerializer serializer;

    public HttpRemoteProxy(String componentId, String host, int port, ItaraSerializer serializer) {
        this.componentId = componentId;
        this.host = host;
        this.port = port;
        this.serializer = serializer;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // ── Context setup ──────────────────────────────────────────────────
        ObservabilityFacade facade = ObservabilityFacade.instance();

        // Capture the context active before this call so fireReturnReceived
        // can restore it. Null means this call will create a root context.
        ItaraContext previousCtx = ItaraContext.current();

        // CALL_SENT — facade resolves context (root or child via bridge),
        // opens OTel CLIENT span, fires passive observers.
        // Returns the resolved context which carries the correct traceId.
        ItaraContext callCtx = facade.fireCallSent(
                componentId, method.getName(), HttpTransport.TYPE);

        boolean error = false;

        log.info("[Itara/HTTP] -> " + method.getName()
                + " on " + componentId + " at " + host + ":" + port);

        try {
            String url = String.format("http://%s:%d/itara/%s/%s",
                    host, port, componentId, method.getName());

            HttpURLConnection connection;
            try {
                connection =
                        (HttpURLConnection) new URL(url).openConnection();
                } catch (IOException e) {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to open connection to '" + componentId
                                + "' at " + host + ":" + port + ": " + e.getMessage(),
                        e);
            }

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type", getContentType());

                // ── Propagate context via W3C headers ──────────────────────────
                String[] headers = ContextPropagation.toHeaders(callCtx);
                connection.setRequestProperty(ContextPropagation.W3C_TRACEPARENT, headers[0]);
                connection.setRequestProperty(ContextPropagation.W3C_TRACESTATE,  headers[1]);

                Object[] safeArgs = (args == null) ? new Object[0] : args;
                byte[] payload;
            try {
                payload = serializer.serializeArgs(safeArgs);
            } catch (Exception e) {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to serialize arguments for '" + method.getName()
                                + "' on '" + componentId + "': " + e.getMessage(),
                        e);
                }

            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            } catch (IOException e) {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to send request to '" + componentId
                                + "' at " + host + ":" + port + ": " + e.getMessage(),
                        e);
            }

            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException e) {
                error = true;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to read response status from '" + componentId
                                + "' at " + host + ":" + port + ": " + e.getMessage(),
                        e);
            }

            // 503 or unexpected status — infrastructure failure, no body guaranteed
            if (status == 503 || status == 405 || status == 400) {
                error = true;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        "io.itara.exception.ItaraInfrastructureException",
                        "Transport failure communicating with '" + componentId
                                + "' at " + host + ":" + port + ": HTTP " + status);
            }

            byte[] responseBytes;
            try (InputStream in = status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                responseBytes = in.readAllBytes();
            } catch (IOException e) {
                error = true;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to read response body from '" + componentId
                                + "' at " + host + ":" + port + ": " + e.getMessage(),
                        e);
            }

            if (status == 200) {
                try {
                    return serializer.deserializeResult(responseBytes, method.getReturnType());
                } catch (Exception e) {
                    error = true;
                    throw new ItaraRemoteException(
                            ItaraRemoteException.ErrorKind.TRANSPORT,
                            e.getClass().getName(),
                            "Failed to deserialize response from '" + componentId
                                    + "." + method.getName() + "': " + e.getMessage(),
                            e);
                }
            }

            // 422 — checked exception from component (declared contract condition)
            // 500 — runtime exception from component (unexpected failure)
            ItaraRemoteException.ErrorKind kind = (status == 422)
                    ? ItaraRemoteException.ErrorKind.CHECKED
                    : ItaraRemoteException.ErrorKind.RUNTIME;

            try {
                throw (ItaraRemoteException) serializer.deserializeResult(
                        responseBytes, ItaraRemoteException.class);
            } catch (ItaraRemoteException e) {
                throw e;
            } catch (Exception e) {
                // Serializer failed to deserialize the error payload — still a transport failure
                error = true;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        e.getClass().getName(),
                        "Failed to deserialize error response from '" + componentId
                                + "." + method.getName() + "': " + e.getMessage(),
                        e);
            }
        } finally {
            // RETURN_RECEIVED — closes OTel CLIENT span, fires passive observers,
            // restores previousCtx (or clears if this was a root call).
            facade.fireReturnReceived(callCtx, previousCtx, componentId, method.getName(), error);
        }
    }


    private String getContentType() {
        switch (serializer.type()) {
            case "json": return "application/json";
            case "java": return "application/x-java-serialized-object";
            default: return "application/octet-stream";
        }
    }
}
