package io.itara.spi.authentication;

/**
 * Determines whether two connections share one authentication instance.
 * Mirrors ItaraSerializerGroupingKey. Implementations MUST implement
 * equals()/hashCode() correctly — records are the natural choice where
 * Java version allows.
 */
public interface ItaraAuthenticationGroupingKey {

    @Override
    boolean equals(Object other);

    @Override
    int hashCode();
}
