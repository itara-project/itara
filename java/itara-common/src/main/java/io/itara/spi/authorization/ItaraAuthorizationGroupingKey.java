package io.itara.spi.authorization;

/**
 * Determines whether two connections share one authorization instance.
 * Mirrors ItaraSerializerGroupingKey. Implementations MUST implement
 * equals()/hashCode() correctly — records are the natural choice where
 * Java version allows.
 */
public interface ItaraAuthorizationGroupingKey {

    @Override
    boolean equals(Object other);

    @Override
    int hashCode();
}
