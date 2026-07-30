package io.itara.spi.serializer;

/**
 * Factory for {@link ItaraSerializer} instances.
 *
 * This is what serializer plugin authors implement and register. The agent
 * discovers factories at startup via META-INF/itara/serializer on the
 * classpath, and uses them to parse connection configs and create serializer
 * instances on demand.
 *
 * The factory is called twice per connection that needs a new serializer
 * instance: once to parse the config, and once to create the instance.
 * Connections that share an existing instance (same grouping key) only
 * trigger parseConfig() — create() is not called again.
 */
public interface ItaraSerializerFactory {

    /**
     * The serializer type identifier this factory handles.
     * Must match the 'id' field in the connection's serializer block
     * in the wiring config. Case-insensitive.
     * Examples: "json", "protobuf"
     */
    String id();

    /**
     * Parse the raw serializer config into a typed, serializer-specific
     * config object.
     *
     * Called once per connection at agent startup. Validate all required
     * parameters here — throw if anything is missing or invalid so that
     * the agent fails fast with a clear error rather than failing silently
     * at call time.
     *
     * @param config  The raw serializer configuration for this connection,
     *                containing the params map.
     * @return        A fully parsed, typed config for this connection.
     * @throws Exception if any required parameter is missing or invalid.
     */
    ItaraSerializerConfig parseConfig(SerializerConfig config) throws Exception;

    /**
     * Create a new serializer instance for the given config.
     *
     * Called at most once per unique grouping key. The config received here
     * is the same object returned by {@link #parseConfig} — no second parse.
     *
     * @param config  The parsed config for this serializer instance.
     * @return        A new, ready-to-use serializer instance.
     * @throws Exception if the instance cannot be created.
     */
    ItaraSerializer create(ItaraSerializerConfig config) throws Exception;
}
