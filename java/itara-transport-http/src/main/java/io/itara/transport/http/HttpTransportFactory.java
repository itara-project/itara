package io.itara.transport.http;

import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.ItaraTransportFactory;
import io.itara.spi.transport.TransportConfig;

/**
 * Factory for HttpTransport instances.
 *
 * Registered via META-INF/itara/transport. The agent discovers this class
 * at startup and uses it to parse connection configs and create transport
 * instances on demand.
 *
 * Expected params:
 *   port           — required for both inbound and outbound connections
 *   host           — required for outbound connections, ignored for inbound
 *   threadPoolSize — optional; number of threads the inbound server uses to
 *                    handle requests concurrently. Defaults to
 *                    HttpTransportConfig.DEFAULT_THREAD_POOL_SIZE.
 */
public class HttpTransportFactory implements ItaraTransportFactory {

    @Override
    public String id() {
        return "http";
    }

    @Override
    public ItaraTransportConfig parseConfig(TransportConfig config) throws Exception {
        String portStr = config.getParams().get("port");
        if (portStr == null || portStr.isBlank()) {
            throw new IllegalArgumentException(
                    "[Itara/HTTP] Missing required param 'port' in transport config");
        }
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "[Itara/HTTP] Param 'port' must be an integer, got: '" + portStr + "'");
        }

        String host = config.getParams().get("host");

        int threadPoolSize = HttpTransportConfig.DEFAULT_THREAD_POOL_SIZE;
        String threadPoolSizeStr = config.getParams().get("threadPoolSize");
        if (threadPoolSizeStr != null && !threadPoolSizeStr.isBlank()) {
            try {
                threadPoolSize = Integer.parseInt(threadPoolSizeStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "[Itara/HTTP] Param 'threadPoolSize' must be an integer, got: '"
                                + threadPoolSizeStr + "'");
            }
            if (threadPoolSize < 1) {
                throw new IllegalArgumentException(
                        "[Itara/HTTP] Param 'threadPoolSize' must be positive, got: "
                                + threadPoolSize);
            }
        }

        return new HttpTransportConfig(host, port, config.isHandleTimeout(), threadPoolSize);
    }

    @Override
    public ItaraTransport create(ItaraTransportConfig config) throws Exception {
        return new HttpTransport((HttpTransportConfig) config);
    }
}
