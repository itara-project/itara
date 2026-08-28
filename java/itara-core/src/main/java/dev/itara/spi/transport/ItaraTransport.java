package dev.itara.spi.transport;

import dev.itara.runtime.DispatchHandler;
import dev.itara.runtime.ItaraCallTarget;

import java.time.Duration;
import java.util.Map;

/**
 * Service Provider Interface for Itara transports.
 *
 * <p>A transport moves bytes. Nothing else.
 *
 * <p>It does not know about serialization format, observability, or the registry.
 * Those concerns belong to the proxy handler and dispatcher, which are owned
 * by the agent and call this transport as a delegate.
 *
 * <p>Outbound: send(bytes) → bytes. The proxy handler owns serialization and
 * observability; the transport owns the connection and the wire protocol.
 *
 * <p>Inbound: the transport calls the DispatchHandler with raw request bytes and
 * receives raw response bytes. The dispatcher owns deserialization, component
 * invocation, result serialization, and observability.
 *
 * <p>Header propagation: send() receives a fully-built map of headers to
 * propagate — not just observability headers, but anything any plugin or
 * the agent itself needs carried across the boundary. The transport
 * forwards these in whatever way its own wire format supports — HTTP
 * headers, Kafka record headers, and so on. It does not build, interpret,
 * or manage them itself.
 *
 * <p>A transport is not supposed to emit its own observability events.
 * Nothing stops it from reading {@code ItaraContext} if something in it
 * is ever relevant, but it must not modify it: by the time send() is
 * called, the context has already been translated into these headers, so
 * a change made here has no effect and is not what gets propagated. On
 * the receiving end, the context is only restored after the transport
 * hands off to the dispatcher, so any event the transport itself tried
 * to create there wouldn't be meaningful either.
 *
 * <p>Implementations live in separate jars (itara-transport-http, etc.), are
 * created by {@link ItaraTransportFactory}
 * and discovered by the agent at startup via META-INF/itara/transport.
 * A single instance may serve multiple components (multiple registered
 * dispatchers) when their connections share the same grouping key.
 */
public interface ItaraTransport {

    /**
     * Send pre-serialized bytes to a remote component and return the
     * raw response bytes.
     *
     * <p>The timeout parameter is always passed by the proxy regardless of whether
     * the transport acts on it. Transports that do not support timeout enforcement
     * MUST silently ignore it. Null means no per-attempt timeout is configured (§14.10)
     *
     * @param target       The call being made — node, component, method. The
     *                     agent has already encoded this into headers
     *                     (CallTargetPropagation); a transport that needs
     *                     component or method for its own routing (e.g. an
     *                     HTTP path) reads it from here, not by parsing
     *                     anything itself.
     * @param payload      Pre-serialized argument bytes
     * @param headers      The fully-built headers to propagate, forwarded
     *                     as-is — not just observability headers, but
     *                     whatever any plugin or the agent needs to
     *                     carry across the boundary
     * @param config       This connection's parsed, transport-specific
     *                     config — the same object this transport's own
     *                     {@link ItaraTransportFactory} produced from
     *                     {@link ItaraTransportFactory#parseConfig}, not
     *                     the raw wiring config
     * @param timeout      The timeout value of the transport
     * @return             Raw response bytes
     * @throws Exception   On any transport-level failure
     */
    byte[] send(ItaraCallTarget target,
                byte[] payload,
                Map<String, String> headers,
                ItaraTransportConfig config,
                Duration timeout) throws Exception;

    /**
     * Register a dispatcher for the given component on this transport instance.
     *
     * <p>Called once per inbound connection during agent startup, before start().
     * The transport accumulates these registrations internally. Nothing is
     * started yet — the transport does not have the full picture until all
     * connections are processed.
     *
     * @param config       The parsed transport config for this connection
     * @param dispatcher   The dispatcher to call with raw request bytes
     */
    void registerListener(ItaraTransportConfig config,
                          DispatchHandler dispatcher);

    /**
     * Start the transport. Called once by the agent after all registerListener()
     * calls are complete. The transport now has the full picture and makes all
     * grouping and resource allocation decisions here — one server per port,
     * one consumer per group, etc.
     *
     * <p>Must return only after the transport is ready to accept connections.
     *
     * @throws Exception if the transport cannot start
     */
    void start() throws Exception;

    /**
     * Stop the transport. Called by the agent's shutdown hook.
     * Must be idempotent.
     */
    void stop();
}
