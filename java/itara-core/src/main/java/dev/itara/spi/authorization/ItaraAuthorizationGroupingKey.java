package dev.itara.spi.authorization;

/**
 * A grouping key that determines whether two connections share the same
 * authorization instance.
 *
 * <p>The registry uses this key to decide whether to reuse an existing
 * authorization instance or create a new one via the factory. Two configs
 * that produce equal keys share one instance; configs that produce unequal
 * keys each get their own.
 *
 * <p>Implementations MUST correctly implement equals() and hashCode().
 * Records are the natural choice in modern Java.
 *
 * <p>Example — an implementation backed by a third-party policy service
 * can group by that service's endpoint, so every connection deciding
 * against the same policy service shares one client instance, rather
 * than opening one per connection.
 */
public interface ItaraAuthorizationGroupingKey {

    /**
     * Compares this grouping key against another.
     *
     * @param other the object to compare against
     * @return true if {@code other} is a grouping key that should share
     *         the same authorization instance as this one
     */
    @Override
    boolean equals(Object other);

    @Override
    int hashCode();
}
