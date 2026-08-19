package io.itara.transport.http;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * One instance per port. Multiple components may be registered as listeners
 * on the same port — each identified by componentId in the request path.
 */
public class HttpTransport implements ItaraTransport {

    private static final Logger log = Logger.getLogger(HttpTransport.class.getName());

    private final Map<String, DispatchHandler> dispatchers = new ConcurrentHashMap<>();

    private final int port;

    private ItaraHttpServer activeServer;

    public HttpTransport(HttpTransportConfig config) {
        this.port = config.getPort();
    }

    @Override
    public byte[] send(ItaraCallTarget target,
                       byte[] payload,
                       Map<String, String> headers,
                       ItaraTransportConfig config,
                       Duration timeout) throws Exception {

        HttpTransportConfig httpConfig = (HttpTransportConfig) config;
        String host = httpConfig.getHost();
        int port    = httpConfig.getPort();
        String componentId = target.getComponent();
        String methodName  = target.getMethod();

        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "[Itara/HTTP] Missing 'host' param for outbound call to '"
                            + componentId + "'");
        }

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

        if (httpConfig.isHandleTimeout() && timeout != null) {
            int millis = (int) timeout.toMillis();
            connection.setConnectTimeout(millis);
            connection.setReadTimeout(millis);
        }

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
    public void registerListener(ItaraTransportConfig config,
                                 DispatchHandler dispatcher) {
        dispatchers.put(dispatcher.getDispatchKey(), dispatcher);
        log.fine("[Itara/HTTP] registered listener for dispatch key='" + dispatcher.getDispatchKey() + "'");
    }

    @Override
    public void start() throws Exception {
        if (dispatchers.isEmpty()) return;
        log.fine("[Itara/HTTP] starting server on port " + port
                + " with " + dispatchers.size() + " registered component(s)");
        activeServer = new ItaraHttpServer(port, dispatchers);
        activeServer.start();
    }

    @Override
    public void stop() {
        if (activeServer != null) {
            activeServer.stop();
            activeServer = null;
        }
    }
}
