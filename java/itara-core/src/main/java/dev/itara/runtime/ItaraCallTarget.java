package dev.itara.runtime;

import java.util.Objects;

/**
 * A transport-agnostic identification of a single call: the node,
 * component, and method being invoked (spec §16.5).
 *
 * <p>Any field may be null — not every exchange pattern has all three (an
 * event has no meaningful reply-target node, for instance; see
 * ExchangePattern.FIRE_AND_FORGET).
 *
 * <p>Only method travels over the wire (see CallTargetPropagation) — node
 * and component are always known locally on both sides (the caller from
 * its own wiring config, the callee from its own dispatcher construction),
 * so both sides build their own ItaraCallTarget directly rather than
 * decoding one end-to-end.
 */
public final class ItaraCallTarget {

    private final String node;
    private final String component;
    private final String method;

    private ItaraCallTarget(String node, String component, String method) {
        this.node      = node;
        this.component = component;
        this.method    = method;
    }

    /**
     * @param node      the topology node being called, or null if not applicable
     * @param component the component being called, or null if not applicable
     * @param method    the method being called, or null if not applicable
     * @return a new call target with the given fields
     */
    public static ItaraCallTarget of(String node, String component, String method) {
        return new ItaraCallTarget(node, component, method);
    }

    /** @return the topology node being called, or null if not applicable */
    public String getNode()      { return node; }
    /** @return the component being called, or null if not applicable */
    public String getComponent() { return component; }
    /** @return the method being called, or null if not applicable */
    public String getMethod()    { return method; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItaraCallTarget)) return false;
        ItaraCallTarget other = (ItaraCallTarget) o;
        return Objects.equals(node, other.node)
                && Objects.equals(component, other.component)
                && Objects.equals(method, other.method);
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, component, method);
    }

    @Override
    public String toString() {
        return "ItaraCallTarget{node='" + node + "', component='" + component + "', method='" + method + "'}";
    }
}
