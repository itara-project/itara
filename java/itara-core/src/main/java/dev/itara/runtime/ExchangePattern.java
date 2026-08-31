package dev.itara.runtime;

/**
 * Describes the messaging exchange pattern of a connection.
 *
 * <p>Set once at startup on both the proxy (sender) and dispatcher (receiver)
 * for a given connection, derived from the wiring configuration and the
 * kind of API/contract involved — not from which transport technology
 * carries the call. All behaviour that differs between synchronous and
 * asynchronous communication — span relationships, failure semantics,
 * header propagation — branches on this value.
 *
 * <ul>
 * <li>{@link #REQUEST_REPLY} — the sender blocks until the receiver
 * responds. Standard parent-child span relationship.</li>
 * <li>{@link #FIRE_AND_FORGET} — the sender does not wait for a response.
 * The receiver operates in a decoupled context: same trace, but a new
 * root span. parentSpanId is not propagated.</li>
 * </ul>
 *
 * <p>See Enterprise Integration Patterns — Message Exchange Patterns.
 */
public enum ExchangePattern {
    /** The sender blocks until the receiver responds. */
    REQUEST_REPLY,
    /** The sender does not wait for a response. */
    FIRE_AND_FORGET
}
