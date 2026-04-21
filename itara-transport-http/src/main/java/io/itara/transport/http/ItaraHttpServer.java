package io.itara.transport.http;

import com.sun.net.httpserver.HttpServer;
import io.itara.runtime.ContextPropagation;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObserverRegistry;
import io.itara.runtime.ItaraRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;

/**
 * Minimal HTTP server that exposes locally-running components to remote callers.
 *
 * Restores ItaraContext from W3C Trace Context headers on inbound requests.
 * Fires onCallReceived on arrival and onReturnSent before responding.
 * Always clears context in finally — never leaks between requests.
 */
public class ItaraHttpServer {

    private final HttpServer server;
    private final ItaraRegistry registry;

    public ItaraHttpServer(int port, ItaraRegistry registry) throws IOException {
        this.registry = registry;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/itara/", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("[Itara/HTTP] Server listening on port "
                + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(1);
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String componentId = parts[2];
        String methodName  = parts[3];

        System.out.println("[Itara/HTTP] <- " + methodName + " on " + componentId);

        // ── Context restoration ────────────────────────────────────────────
        String traceparent = exchange.getRequestHeaders()
                .getFirst(ContextPropagation.W3C_TRACEPARENT);
        String tracestate  = exchange.getRequestHeaders()
                .getFirst(ContextPropagation.W3C_TRACESTATE);

        ItaraContext ctx = ContextPropagation.fromHeaders(traceparent, tracestate);
        if (ctx == null) {
            ctx = ItaraContext.newRoot(componentId);
        }
        ItaraContext.set(ctx);

        ObserverRegistry observers = ObserverRegistry.instance();

        // ── onCallReceived ─────────────────────────────────────────────────
        observers.fireCallReceived(ctx, componentId, methodName);

        try {
            Object[] args;
            try (ObjectInputStream ois =
                         new ObjectInputStream(exchange.getRequestBody())) {
                args = (Object[]) ois.readObject();
            }

            Object instance = registry.get(componentId, Object.class);

            Method method = findMethod(instance.getClass(), methodName);
            if (method == null) {
                throw new NoSuchMethodException(
                        "No method '" + methodName + "' on "
                        + instance.getClass().getName());
            }

            Object result = method.invoke(instance, args);

            // ── onReturnSent (success) ─────────────────────────────────────
            observers.fireReturnSent(ctx, componentId, methodName, false);

            sendObject(exchange, 200, result);

        } catch (Throwable t) {
            System.err.println("[Itara/HTTP] Error handling " + methodName
                    + " on " + componentId + ": " + t.getMessage());

            // ── onReturnSent (error) ───────────────────────────────────────
            observers.fireReturnSent(ctx, componentId, methodName, true);

            sendObject(exchange, 500, t);

        } finally {
            ItaraContext.clear();
        }
    }

    private void sendObject(com.sun.net.httpserver.HttpExchange exchange,
                            int status, Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        byte[] bytes = baos.toByteArray();
        exchange.getResponseHeaders().set("Content-Type",
                "application/x-java-serialized-object");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}
