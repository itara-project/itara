package io.itara.spi.authentication;

/**
 * A parsed, implementation-specific configuration for a single
 * connection's authentication. Produced by
 * {@link ItaraAuthenticationFactory#parseConfig}. Mirrors
 * ItaraSerializerConfig.
 */
public interface ItaraAuthenticationConfig {

    ItaraAuthenticationGroupingKey groupingKey();
}
