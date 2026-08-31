package dev.itara.spi.authentication;

/**
 * A grouping key that determines whether two connections share the same
 * authentication instance.
 *
 * <p>The registry uses this key to decide whether to reuse an existing
 * authentication instance or create a new one via the factory. Two configs
 * that produce equal keys share one instance; configs that produce unequal
 * keys each get their own.
 *
 * <p>Implementations MUST correctly implement equals() and hashCode().
 * Records are the natural choice in modern Java.
 *
 * <p>Example — an implementation that verifies against a shared JWKS
 * endpoint can group by that endpoint's URL, so every connection
 * verifying against the same endpoint shares one client instance,
 * rather than opening one per connection.
 */
public interface ItaraAuthenticationGroupingKey {

    /**
     * Compares this grouping key against another.
     *
     * @param other the object to compare against
     * @return true if {@code other} is a grouping key that should share
     *         the same authentication instance as this one
     */
    @Override
    boolean equals(Object other);

    @Override
    int hashCode();
}
