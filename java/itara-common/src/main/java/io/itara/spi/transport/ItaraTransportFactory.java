package io.itara.spi.transport;

/**
 * Factory for {@link ItaraTransport} instances.
 *
 * This is what transport plugin authors implement and register. The agent
 * discovers factories at startup via META-INF/itara/transport on the
 * classpath, and uses them to parse connection configs and create transport
 * instances on demand.
 *
 * The factory is called twice per connection that needs a new transport
 * instance: once to parse the config, and once to create the instance.
 * Connections that share an existing instance (same grouping key) only
 * trigger parseConfig() — create() is not called again.
 */
public interface ItaraTransportFactory {

    /**
     * The transport type identifier this factory handles.
     * Must match the 'id' field in the connection's transport block
     * in the wiring config. Case-insensitive.
     * Examples: "http", "kafka"
     */
    String id();

    /**
     * Parse the raw transport config into a typed, transport-specific
     * config object.
     *
     * Called once per connection at agent startup. Validate all required
     * parameters here — throw if anything is missing or invalid so that
     * the agent fails fast with a clear error rather than failing silently
     * at call time.
     *
     * @param config  The raw transport configuration for this connection,
     *                containing handleTimeout and the params map.
     * @return        A fully parsed, typed config for this connection.
     * @throws Exception if any required parameter is missing or invalid.
     */
    ItaraTransportConfig parseConfig(TransportConfig config) throws Exception;

    /**
     * Create a new transport instance for the given config.
     *
     * Called at most once per unique grouping key. The config received here
     * is the same object returned by {@link #parseConfig} — no second parse.
     *
     * @param config  The parsed config for this transport instance.
     * @return        A new, unconfigured transport instance. The agent will
     *                call registerListener() and/or use it for send() next.
     * @throws Exception if the instance cannot be created.
     */
    ItaraTransport create(ItaraTransportConfig config) throws Exception;
}
