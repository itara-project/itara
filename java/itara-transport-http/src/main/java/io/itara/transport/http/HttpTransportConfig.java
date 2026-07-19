package io.itara.transport.http;

import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.ItaraTransportGroupingKey;

/**
 * Parsed configuration for a single HTTP transport connection.
 *
 * Serves as both the transport config and its own grouping key — two
 * connections that share a port share one HttpTransport instance,
 * regardless of host (which is outbound-only and irrelevant for grouping).
 *
 * host           — remote host for outbound calls. Null for inbound-only connections.
 * port           — port number. Required. Used as the grouping key.
 * handleTimeout  — whether to enforce the per-call timeout natively via the
 *                  HTTP client's connect/read timeout settings.
 * threadPoolSize — number of threads the inbound server uses to handle
 *                  requests concurrently. Inbound-only; does not affect
 *                  grouping, so the config that first creates the transport
 *                  instance for a port determines the pool size.
 */
public final class HttpTransportConfig implements ItaraTransportConfig, ItaraTransportGroupingKey {

    /** Default inbound server thread pool size when 'threadPoolSize' is not configured. */
    public static final int DEFAULT_THREAD_POOL_SIZE = 10;

    private final String host;
    private final int port;
    private final boolean handleTimeout;
    private final int threadPoolSize;

    public HttpTransportConfig(String host, int port, boolean handleTimeout) {
        this(host, port, handleTimeout, DEFAULT_THREAD_POOL_SIZE);
    }

    public HttpTransportConfig(String host, int port, boolean handleTimeout, int threadPoolSize) {
        this.host = host;
        this.port = port;
        this.handleTimeout = handleTimeout;
        this.threadPoolSize = threadPoolSize;
    }

    public String getHost()          { return host; }
    public int getPort()             { return port; }
    public boolean isHandleTimeout() { return handleTimeout; }
    public int getThreadPoolSize()   { return threadPoolSize; }

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
