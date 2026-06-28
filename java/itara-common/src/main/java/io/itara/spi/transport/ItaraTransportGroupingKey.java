package io.itara.spi.transport;

/**
 * A grouping key that determines whether two connections share the same
 * transport instance.
 *
 * The registry uses this key to decide whether to reuse an existing transport
 * instance or create a new one via the factory. Two configs that produce equal
 * keys share one instance; configs that produce unequal keys each get their own.
 *
 * Implementations MUST correctly implement equals() and hashCode().
 * Records are the natural choice in modern Java.
 *
 * Example — HTTP groups by port, so two connections to port 8081 share one
 * HttpTransport instance while a connection to port 8082 gets its own.
 */
public interface ItaraTransportGroupingKey {

    /**
     * Must be consistent with hashCode().
     */
    @Override
    boolean equals(Object other);

    /**
     * Must be consistent with equals().
     */
    @Override
    int hashCode();
}
