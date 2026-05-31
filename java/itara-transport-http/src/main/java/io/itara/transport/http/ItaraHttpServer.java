package io.itara.transport.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.itara.runtime.ContextPropagation;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.ItaraRegistry;
import io.itara.spi.ItaraSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal HTTP server that exposes locally-running components to remote callers.
 *
 * Started by the agent for any component that has inbound HTTP connections
 * in the wiring config (i.e., another JVM's proxy will be calling this one).
 *
 * Endpoint pattern:
 *   POST /itara/{componentId}/{methodName}
 *
 * Error handling:
 *   400 — malformed request (bad path, unknown method, unreadable arguments).
 *         The caller sent something this server cannot process. The component
 *         was never invoked.
 *   500 — component invocation failed. The component threw an exception during
 *         execution. The response body contains a serialized error payload that
 *         the caller can deserialize into an ItaraRemoteException.
 *   503 — infrastructure failure. Itara itself failed — registry lookup,
 *         activation, or response serialization. The component may or may not
 *         have been invoked. The caller should treat this as a transport error,
 *         not a business error. No response body is guaranteed.
 *
 * The component instance is retrieved from the registry on each call.
 * Activation is triggered on first access if not already initialized.
 *
 * Uses JDK's built-in HttpServer — no external dependencies.
 *
 * Restores ItaraContext from W3C Trace Context headers on inbound requests.
 * Fires onCallReceived on arrival and onReturnSent before responding.
 * Always clears context in finally — never leaks between requests.
 */
public class ItaraHttpServer {

    private static final Logger log = Logger.getLogger(ItaraHttpServer.class.getName());

    private final HttpServer server;
    private final ItaraRegistry registry;
    private final ItaraSerializer serializer;

    public ItaraHttpServer(int port, ItaraRegistry registry, ItaraSerializer serializer) throws IOException {
        this.registry = registry;
        this.serializer = serializer;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/itara/", this::handle);
    }

    public void start() {
        server.start();
        log.info("[Itara/HTTP] Server listening on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(1);
        log.info("[Itara/HTTP] Server stopped");
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendEmpty(exchange, 405);
            return;
        }

        // Parse /itara/{componentId}/{methodName}
        String[] parts = exchange.getRequestURI().getPath().split("/");
        // Expected: ["", "itara", componentId, methodName]
        if (parts.length < 4 || parts[2].isBlank() || parts[3].isBlank()) {
            log.warning("[Itara/HTTP] Rejected malformed path: "
                    + exchange.getRequestURI().getPath());
            sendEmpty(exchange, 400);
            return;
        }

        String componentId = parts[2];
        String methodName = parts[3];

        log.info("[Itara/HTTP] <- " + methodName + " on " + componentId);

        // ── Context restoration ────────────────────────────────────────────/
        String traceparent = exchange.getRequestHeaders()
                .getFirst(ContextPropagation.W3C_TRACEPARENT);
        String tracestate = exchange.getRequestHeaders()
                .getFirst(ContextPropagation.W3C_TRACESTATE);

        // Restore incoming context from W3C headers if present.
        // Null means no incoming context — fireCallReceived will create a root.
        ItaraContext incomingCtx = ContextPropagation.fromHeaders(traceparent, tracestate);

        ObservabilityFacade facade = ObservabilityFacade.instance();

        // CALL_RECEIVED — bridge resolves context (restored or new root),
        // opens OTel SERVER span, fires passive observers.
        // Returns the resolved context for use in the finally block.
        ItaraContext callCtx = facade.fireCallReceived(
                incomingCtx, componentId, methodName, HttpTransport.TYPE);

        boolean error = false;
        try {
            // Registry lookup and activation — infrastructure concern.
            // Failure here means Itara is misconfigured or the component
            // failed to activate. Return 503 — this is not the caller's fault,
            // but it is also not a business error from the component.
            Object instance;
            try {
                instance = registry.get(componentId, Object.class);
            } catch (Exception e) {
                log.log(Level.SEVERE, "[Itara/HTTP] Failed to retrieve component '"
                        + componentId + "' from registry. "
                        + "This is an infrastructure failure — check activation.", e);
                sendEmpty(exchange, 503);
                error = true;
                return;
            }

            // Method resolution — if the method does not exist, the caller sent
            // a request this server cannot fulfill. Return 400.
            Method method = findMethod(instance.getClass(), methodName);
            if (method == null) {
                log.warning("[Itara/HTTP] Method '" + methodName
                        + "' not found on component '" + componentId + "'. "
                        + "Caller may be using a stale or mismatched contract.");
                sendEmpty(exchange, 400);
                error = true;
                return;
            }

            // Argument deserialization — if the caller sent unreadable bytes,
            // that is the caller's fault. Return 400.
            Object[] args;
            try {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                args = serializer.deserializeArgs(requestBytes, method.getParameterTypes());
            } catch (Exception e) {
                log.log(Level.WARNING, "[Itara/HTTP] Failed to deserialize arguments for '"
                        + methodName + "' on '" + componentId + "'. "
                        + "Caller may have sent a malformed or incompatible payload.", e);
                sendEmpty(exchange, 400);
                error = true;
                return;
            }

            // Component invocation — if the component throws, that is a business
            // error. Serialize it and return 500 so the caller can reconstruct it
            // as an ItaraRemoteException.
            Object result;
            try {
                result = method.invoke(instance, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException || cause instanceof Error) {
                    // Unexpected failure — component threw something outside its contract
                    log.log(Level.WARNING, "[Itara/HTTP] Component '" + componentId
                            + "." + methodName + "' threw an unexpected runtime exception: "
                            + cause.getMessage(), cause);
                    sendResult(exchange, 500, cause);
                } else {
                    // Checked exception — declared contract condition, caller should handle
                    log.log(Level.INFO, "[Itara/HTTP] Component '" + componentId
                            + "." + methodName + "' threw a checked exception: "
                            + cause.getMessage(), cause);
                    sendResult(exchange, 422, cause);
                }
                return;
            } catch (Exception e) {
                log.log(Level.SEVERE, "[Itara/HTTP] Failed to invoke '"
                        + methodName + "' on '" + componentId + "'.", e);
                sendEmpty(exchange, 503);
                error = true;
                return;
            }

            // Response serialization — if the serializer fails, we cannot send
            // a meaningful error body either. Log loudly and send 503 with no body.
            // This indicates an Itara infrastructure failure.
            sendResult(exchange, 200, result);
        } catch (Throwable t) {
            error = true;
        } finally {
            // RETURN_SENT — closes OTel SERVER span, fires passive observers,
            // clears ThreadLocal context. Always runs regardless of outcome.
            facade.fireReturnSent(callCtx, componentId, methodName, error);
        }
    }

    /**
     * Serialize and send a result or exception as the response body.
     *
     * If the serializer itself throws, falls back to a 503 with no body.
     * At that point no serializer-based error response can be trusted either,
     * so the safest option is an empty response that signals infrastructure failure.
     */
    private void sendResult(HttpExchange exchange, int status, Object obj) throws Exception {
        byte[] bytes;
        try {
            bytes = serializer.serializeResult(obj);
        } catch (Exception e) {
            log.log(Level.SEVERE, "[Itara/HTTP] Serializer failed to serialize response. "
                    + "This is an Itara infrastructure failure.", e);
            sendEmpty(exchange, 503);
            throw e;
        }
        exchange.getResponseHeaders().set("Content-Type", getContentType());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Send a response with no body. Used for protocol-level errors where
     * no meaningful payload can or should be sent.
     */
    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private String getContentType() {
        switch (serializer.type()) {
            case "json": return "application/json";
            case "java": return "application/x-java-serialized-object";
            default: return "application/octet-stream";
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}
