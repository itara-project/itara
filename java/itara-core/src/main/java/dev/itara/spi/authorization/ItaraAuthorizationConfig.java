package dev.itara.spi.authorization;

/**
 * A parsed, implementation-specific configuration for a single
 * connection's authorization.
 *
 * <p>Produced by {@link ItaraAuthorizationFactory#parseConfig} from the raw
 * {@link AuthorizationConfig} supplied by the agent. Passed back in on
 * every call to {@link ItaraAuthorization#authorize}, since an instance
 * may be shared (per its grouping key) across connections that each
 * still need their own configuration applied.
 *
 * <p>The registry calls {@link #groupingKey()} to decide whether an existing
 * authorization instance can serve this connection or a new one must be
 * created. If a new instance is needed, {@link ItaraAuthorizationFactory#create}
 * receives this object directly — no second parse.
 *
 * <p>Implementations should be immutable. Records are the natural choice.
 */
public interface ItaraAuthorizationConfig {

    /**
     * Returns the grouping key for this configuration.
     *
     * <p>Two configs with equal grouping keys share one authorization
     * instance. Two configs with unequal keys each get their own instance.
     *
     * @return the grouping key for this configuration
     */
    ItaraAuthorizationGroupingKey groupingKey();
}
