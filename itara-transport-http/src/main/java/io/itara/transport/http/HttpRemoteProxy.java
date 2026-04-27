package io.itara.transport.http;

import io.itara.runtime.ContextPropagation;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObserverRegistry;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * InvocationHandler for remote component calls over HTTP.
 *
 * Fires onCallSent before the call and onReturnReceived after.
 * Propagates ItaraContext to the remote side via W3C Trace Context headers.
 * Always clears context in finally — never leaks between requests.
 */
public class HttpRemoteProxy implements InvocationHandler {

    private final String componentId;
    private final String host;
    private final int port;

    public HttpRemoteProxy(String componentId, String host, int port) {
        this.componentId = componentId;
        this.host = host;
        this.port = port;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // ── Context setup ──────────────────────────────────────────────────
        ItaraContext ctx = ItaraContext.current();
        boolean isRootCaller = (ctx == null);
        if (isRootCaller) {
            ctx = ItaraContext.newRoot(componentId);
            ItaraContext.set(ctx);
        }
        ItaraContext callCtx = ctx.newChildSpan(componentId);
        ItaraContext.set(callCtx);

        ObserverRegistry observers = ObserverRegistry.instance();
        boolean error = false;

        // ── onCallSent ─────────────────────────────────────────────────────
        observers.fireCallSent(callCtx, componentId, method.getName());

        String url = String.format("http://%s:%d/itara/%s/%s",
                host, port, componentId, method.getName());

        System.out.println("[Itara/HTTP] -> " + method.getName()
                + " on " + componentId + " at " + host + ":" + port);

        try {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type",
                    "application/x-java-serialized-object");

            // ── Propagate context via W3C headers ──────────────────────────
            String[] headers = ContextPropagation.toHeaders(callCtx);
            connection.setRequestProperty(ContextPropagation.W3C_TRACEPARENT, headers[0]);
            connection.setRequestProperty(ContextPropagation.W3C_TRACESTATE,  headers[1]);

            Object[] safeArgs = (args == null) ? new Object[0] : args;
            try (ObjectOutputStream oos =
                         new ObjectOutputStream(connection.getOutputStream())) {
                oos.writeObject(safeArgs);
                oos.flush();
            }

            int status = connection.getResponseCode();
            InputStream responseStream = (status < 500)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            try (ObjectInputStream ois = new ObjectInputStream(responseStream)) {
                Object response = ois.readObject();
                if (status >= 500 && response instanceof Throwable t) {
                    error = true;
                    throw t;
                }
                return response;
            }

        } catch (Throwable t) {
            error = true;
            throw t;

        } finally {
            // ── onReturnReceived ───────────────────────────────────────────
            observers.fireReturnReceived(callCtx, componentId, method.getName(), error);

            if (isRootCaller) {
                ItaraContext.clear();
            } else {
                ItaraContext.set(ctx);
            }
        }
    }
}
