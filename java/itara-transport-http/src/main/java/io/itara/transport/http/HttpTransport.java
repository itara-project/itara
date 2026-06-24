package io.itara.transport.http;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;
import io.itara.spi.ItaraTransport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/**
 * HTTP transport implementation.
 *
 * Moves bytes. Nothing else.
 *
 * Outbound: opens a connection, injects W3C trace headers from the provided
 * context, writes payload bytes, reads response bytes, maps HTTP status codes
 * to exceptions. No serialization. No observability.
 *
 * Inbound: starts an ItaraHttpServer that parses the request path and delivers
 * raw bytes to the DispatchHandler. The dispatcher owns everything else.
 *
 * Discovered by the agent via META-INF/itara/transport.
 *
 * Properties used:
 *   host  - remote host (outbound)
 *   port  - port number (inbound and outbound)
 */
public class HttpTransport implements ItaraTransport {

    public static String TYPE = "http";

    private static final Logger log = Logger.getLogger(HttpTransport.class.getName());

    private ItaraHttpServer activeServer;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] send(String componentId,
                       String methodName,
                       byte[] payload,
                       Map<String, String> headers,
                       Map<String, String> properties,
                       Duration timeout) throws Exception {

        String host = required(properties, "host", componentId);
        int port = requiredInt(properties, "port", componentId);

        String url = String.format("http://%s:%d/itara/%s/%s", host, port, componentId, methodName);
        log.info("[Itara/HTTP] -> " + methodName + " on " + componentId + " at " + host + ":" + port);

        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
        } catch (IOException e) {
            throw new IOException("Failed to open connection to '" + componentId
                    + "' at " + host + ":" + port + ": " + e.getMessage(), e);
        }

        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("Content-Type", "application/octet-stream");

        // Inject the headers
        headers.forEach(connection::setRequestProperty);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException e) {
            throw new IOException("Failed to read response status from '" + componentId
                    + "' at " + host + ":" + port + ": " + e.getMessage(), e);
        }

        // 405 and 400 are protocol-level failures - no serialized payload on the wire.
        // Everything else (422, 500, 503) carries a dispatcher-serialized error payload.
        if (status == ItaraHttpStatus.METHOD_NOT_FOUND || status == ItaraHttpStatus.BAD_REQUEST) {
            throw new IOException("Transport failure from '" + componentId
                    + "' at " + host + ":" + port + ": HTTP " + status);
        }

        try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            byte[] responseBytes = (in != null) ? in.readAllBytes() : new byte[0];
            // Signal error status to the proxy handler via a tagged response.
            // The proxy handler will rethrow appropriately after deserialization.
            if (status != ItaraHttpStatus.OK) {
                throw new ItaraRemoteException(responseBytes);
            }
            return responseBytes;
        }
    }

    @Override
    public void startListener(String componentId,
                              Map<String, String> properties,
                              DispatchHandler dispatcher) {
        int port = requiredInt(properties, "port", componentId);
        try {
            activeServer = new ItaraHttpServer(port, dispatcher);
            activeServer.start();
        } catch (Exception e) {
            throw new RuntimeException("[Itara/HTTP] Failed to start HTTP listener on port "
                    + port + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void stopListener() {
        if (activeServer != null) {
            activeServer.stop();
            activeServer = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String required(Map<String, String> props, String key, String componentId) {
        String value = props.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[Itara/HTTP] Missing required property '" + key
                    + "' for component '" + componentId + "'");
        }
        return value;
    }

    private int requiredInt(Map<String, String> props, String key, String componentId) {
        String value = required(props, key, componentId);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "[Itara/HTTP] Property '" + key + "' must be an integer, got: "
                    + value);
        }
    }
}
