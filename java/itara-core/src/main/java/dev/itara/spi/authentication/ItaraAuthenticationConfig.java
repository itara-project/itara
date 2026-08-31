package dev.itara.spi.authentication;

/**
 * A parsed, implementation-specific configuration for a single
 * connection's authentication.
 *
 * <p>Produced by {@link ItaraAuthenticationFactory#parseConfig} from the raw
 * {@link AuthenticationConfig} supplied by the agent. The factory parses the
 * params map once and returns a typed config object — the raw params are
 * not passed further.
 *
 * <p>The registry calls {@link #groupingKey()} to decide whether an existing
 * authentication instance can serve this connection or a new one must be
 * created. If a new instance is needed, {@link ItaraAuthenticationFactory#create}
 * receives this object directly — no second parse.
 *
 * <p>Implementations should be immutable. Records are the natural choice.
 */
public interface ItaraAuthenticationConfig {

    /**
     * Returns the grouping key for this configuration.
     *
     * <p>Two configs with equal grouping keys share one authentication
     * instance. Two configs with unequal keys each get their own instance.
     *
     * @return the grouping key for this configuration
     */
    ItaraAuthenticationGroupingKey groupingKey();
}
