package io.itara.transport.http;

import io.itara.runtime.ItaraRegistry;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;

import java.util.Map;

/**
 * HTTP transport implementation.
 *
 * Handles outbound HTTP calls (via HttpRemoteProxy + RemoteProxyFactory)
 * and inbound HTTP calls (via ItaraHttpServer).
 *
 * Discovered by the agent via META-INF/itara/transport in this jar.
 *
 * Properties used:
 *   host  - remote host for outbound connections
 *   port  - port number for both inbound and outbound
 *   to    - component id (used in URL path for outbound)
 */
public class HttpTransport implements ItaraTransport {

    private ItaraHttpServer activeServer;

    @Override
    public String type() {
        return "http";
    }

    @Override
    public Object createProxy(String componentId,
                              Class<?> contractClass,
                              Map<String, String> properties,
                              ClassLoader classLoader,
                              ItaraSerializer serializer) {
        String host = required(properties, "host", componentId);
        int port = requiredInt(properties, "port", componentId);

        return RemoteProxyFactory.createHttpProxy(
                contractClass, componentId, host, port, classLoader, serializer);
    }

    @Override
    public void startListener(String componentId,
                              Map<String, String> properties,
                              ItaraRegistry registry,
                              ItaraSerializer serializer) {
        int port = requiredInt(properties, "port", componentId);

        try {
            activeServer = new ItaraHttpServer(port, registry, serializer);
            activeServer.start();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[Itara/HTTP] Failed to start HTTP listener on port "
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
