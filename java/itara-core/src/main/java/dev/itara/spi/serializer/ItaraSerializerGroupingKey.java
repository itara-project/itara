package dev.itara.spi.serializer;

/**
 * A grouping key that determines whether two connections share the same
 * serializer instance.
 *
 * <p>The registry uses this key to decide whether to reuse an existing
 * serializer instance or create a new one via the factory. Two configs that
 * produce equal keys share one instance; configs that produce unequal keys
 * each get their own.
 *
 * <p>Implementations MUST correctly implement equals() and hashCode().
 * Records are the natural choice in modern Java.
 *
 * <p>Example — a serializer with no meaningful per-connection state can return
 * a single constant key so every connection shares one instance. A
 * serializer whose behaviour depends on a connection parameter (e.g. a
 * schema registry URL) would group by that parameter instead.
 */
public interface ItaraSerializerGroupingKey {

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
