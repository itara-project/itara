package io.itara.spi.authentication;

/**
 * Factory for {@link ItaraAuthentication} instances. Discovered by the
 * agent via META-INF/itara/authentication on the classpath (spec §15.3).
 * Mirrors ItaraSerializerFactory — parseConfig() is called once per
 * connection; create() is called at most once per unique grouping key.
 */
public interface ItaraAuthenticationFactory {

    /**
     * The type identifier this factory handles. Must match the 'id'
     * field in the connection's authentication block. Case-insensitive.
     */
    String id();

    ItaraAuthenticationConfig parseConfig(AuthenticationConfig config) throws Exception;

    ItaraAuthentication create(ItaraAuthenticationConfig config) throws Exception;
}
