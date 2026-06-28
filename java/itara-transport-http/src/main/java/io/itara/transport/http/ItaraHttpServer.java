package io.itara.transport.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP server for inbound Itara calls.
 *
 * Parses the request path, extracts all inbound headers, reads request bytes,
 * calls the dispatcher with the raw header map, writes response bytes.
 * That is all.
 *
 * No serialization. No observability. No context management. No registry access.
 * The DispatchHandler (via ObservabilityFacade) owns all of that.
 *
 * Error handling:
 *   400 — malformed path or unreadable request bytes (transport-level, before dispatch)
 *   405 — non-POST request method
 *   422 — dispatcher threw a CHECKED ItaraRemoteException (contract condition)
 *   500 — dispatcher threw a RUNTIME ItaraRemoteException (component failure)
 *   503 — dispatcher threw a TRANSPORT ItaraRemoteException, or unexpected infrastructure failure
 *
 * The response body on 500/422 is whatever the dispatcher returned as the
 * serialized error — the proxy handler on the other side deserializes it.
 *
 * Endpoint: POST /itara/{componentId}/{methodName}
 */
public class ItaraHttpServer {

    private static final Logger log = Logger.getLogger(ItaraHttpServer.class.getName());

    private final HttpServer server;
    private final Map<String, DispatchHandler> dispatchers;

    public ItaraHttpServer(int port, Map<String, DispatchHandler> dispatchers) throws IOException {
        this.dispatchers = dispatchers;
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
            sendEmpty(exchange, ItaraHttpStatus.METHOD_NOT_FOUND);
            return;
        }

        // Parse /itara/{componentId}/{methodName}
        String[] parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length < 4 || parts[2].isBlank() || parts[3].isBlank()) {
            log.warning("[Itara/HTTP] Rejected malformed path: "
                    + exchange.getRequestURI().getPath());
            sendEmpty(exchange, ItaraHttpStatus.BAD_REQUEST);
            return;
        }

        String componentId = parts[2];
        String methodName = parts[3];

        log.info("[Itara/HTTP] <- " + methodName + " on " + componentId);

        // Normalise to lowercase — Itara's header contract is lowercase keys,
        // matching HTTP/2 convention and OTel's own W3C headers.
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                headers.put(key.toLowerCase(), values.get(0));
            }
        });

        byte[] requestBytes;
        try {
            requestBytes = exchange.getRequestBody().readAllBytes();
        } catch (IOException e) {
            log.warning("[Itara/HTTP] Failed to read request body for '"
                    + methodName + "' on '" + componentId + "'");
            sendEmpty(exchange, ItaraHttpStatus.BAD_REQUEST);
            return;
        }

        DispatchHandler dispatcher = dispatchers.get(componentId);
        if (dispatcher == null) {
            log.warning("[Itara/HTTP] No dispatcher registered for component '"
                    + componentId + "' on this server");
            sendEmpty(exchange, ItaraHttpStatus.BAD_REQUEST);
            return;
        }

        byte[] responseBytes;
        try {
            responseBytes = dispatcher.dispatch(componentId, methodName, requestBytes, headers);
        } catch (ItaraRemoteException e) {
            sendBytes(exchange, ItaraHttpStatus.forErrorKind(e.getErrorKind()), e.getSerializedPayload());
            return;
        } catch (Exception e) {
            log.log(Level.SEVERE, "[Itara/HTTP] Unexpected dispatcher failure for '"
                    + methodName + "' on '" + componentId + "'", e);
            sendEmpty(exchange, ItaraHttpStatus.TRANSPORT_ERROR);
            return;
        }

        sendBytes(exchange, ItaraHttpStatus.OK, responseBytes);
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            sendEmpty(exchange, status);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Send a response with no body. Used for protocol-level errors where
     * no meaningful payload can or should be sent.
     */
    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
