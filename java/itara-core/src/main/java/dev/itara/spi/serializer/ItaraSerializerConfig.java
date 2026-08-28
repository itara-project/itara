package dev.itara.spi.serializer;

/**
 * A parsed, serializer-specific configuration for a single connection.
 *
 * <p>Produced by {@link ItaraSerializerFactory#parseConfig} from the raw
 * {@link SerializerConfig} supplied by the agent. The factory parses the
 * params map once and returns a typed config object — the raw params are
 * not passed further.
 *
 * <p>The registry calls {@link #groupingKey()} to decide whether an existing
 * serializer instance can serve this connection or a new one must be
 * created. If a new instance is needed, {@link ItaraSerializerFactory#create}
 * receives this object directly — no second parse.
 *
 * <p>Implementations should be immutable. Records are the natural choice.
 */
public interface ItaraSerializerConfig {

    /**
     * Returns the grouping key for this configuration.
     *
     * <p>Two configs with equal grouping keys share one serializer instance.
     * Two configs with unequal keys each get their own instance.
     */
    ItaraSerializerGroupingKey groupingKey();
}
