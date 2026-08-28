package dev.itara.transport.http;

import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.spi.transport.ItaraTransportGroupingKey;

/**
 * Parsed configuration for a single HTTP transport connection.
 *
 * Serves as both the transport config and its own grouping key — two
 * connections that share a port share one HttpTransport instance,
 * regardless of host (which is outbound-only and irrelevant for grouping).
 *
 * host          — remote host for outbound calls. Null for inbound-only connections.
 * port          — port number. Required. Used as the grouping key.
 * handleTimeout — whether to enforce the per-call timeout natively via the
 *                 HTTP client's connect/read timeout settings.
 */
public final class HttpTransportConfig implements ItaraTransportConfig, ItaraTransportGroupingKey {

    private final String host;
    private final int port;
    private final boolean handleTimeout;

    public HttpTransportConfig(String host, int port, boolean handleTimeout) {
        this.host = host;
        this.port = port;
        this.handleTimeout = handleTimeout;
    }

    public String getHost()          { return host; }
    public int getPort()             { return port; }
    public boolean isHandleTimeout() { return handleTimeout; }

    @Override
    public ItaraTransportGroupingKey groupingKey() {
        return this;
    }

    /**
     * Two HTTP connections group together if and only if they share a port.
     * Host is outbound-only and does not affect grouping.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HttpTransportConfig)) return false;
        return this.port == ((HttpTransportConfig) other).port;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(port);
    }
}
