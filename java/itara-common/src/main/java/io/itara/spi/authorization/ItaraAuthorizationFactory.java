package io.itara.spi.authorization;

/**
 * Factory for {@link ItaraAuthorization} instances. Discovered by the
 * agent via META-INF/itara/authorization on the classpath (spec §16.3).
 * Mirrors ItaraSerializerFactory — parseConfig() is called once per
 * connection; create() is called at most once per unique grouping key.
 */
public interface ItaraAuthorizationFactory {

    /**
     * The type identifier this factory handles. Must match the 'id'
     * field in the connection's authorization block. Case-insensitive.
     */
    String id();

    ItaraAuthorizationConfig parseConfig(AuthorizationConfig config) throws Exception;

    ItaraAuthorization create(ItaraAuthorizationConfig config) throws Exception;
}
