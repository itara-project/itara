package io.itara.runtime;

/**
 * Describes the messaging exchange pattern of a connection.
 *
 * Set once at startup on both the proxy (sender) and dispatcher (receiver)
 * for a given connection. All behaviour that differs between synchronous
 * and asynchronous communication — span relationships, failure semantics,
 * header propagation — branches on this value.
 *
 * REQUEST_REPLY   — the sender blocks until the receiver responds.
 *                   Standard parent-child span relationship.
 *                   Used for HTTP and direct connections.
 *
 * FIRE_AND_FORGET — the sender does not wait for a response. The receiver
 *                   operates in a decoupled context: same trace, but a new
 *                   root span. parentSpanId is not propagated.
 *                   Used for Kafka and other async transports.
 *
 * See Enterprise Integration Patterns — Message Exchange Patterns.
 */
public enum ExchangePattern {
    REQUEST_REPLY,
    FIRE_AND_FORGET
}
