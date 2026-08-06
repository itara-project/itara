package io.itara.spi.authorization;

/**
 * A parsed, implementation-specific configuration for a single
 * connection's authorization. Produced by
 * {@link ItaraAuthorizationFactory#parseConfig}. Passed back in on every
 * call to {@link ItaraAuthorization#authorize}, since an instance may be
 * shared (per its grouping key) across connections that each still need
 * their own configuration applied. Mirrors ItaraSerializerConfig.
 */
public interface ItaraAuthorizationConfig {

    ItaraAuthorizationGroupingKey groupingKey();
}
