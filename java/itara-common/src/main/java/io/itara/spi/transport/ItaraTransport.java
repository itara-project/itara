package io.itara.spi.transport;

import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraCallTarget;

import java.time.Duration;
import java.util.Map;

/**
 * Service Provider Interface for Itara transports.
 *
 * A transport moves bytes. Nothing else.
 *
 * It does not know about serialization format, observability, or the registry.
 * Those concerns belong to the proxy handler and dispatcher, which are owned
 * by the agent and call this transport as a delegate.
 *
 * Outbound: send(bytes) → bytes. The proxy handler owns serialization and
 * observability; the transport owns the connection and the wire protocol.
 *
 * Inbound: the transport calls the DispatchHandler with raw request bytes and
 * receives raw response bytes. The dispatcher owns deserialization, component
 * invocation, result serialization, and observability.
 *
 * Context propagation: the transport receives an ItaraContext on send() so it
 * can inject W3C trace headers. It reads from the context but does not create,
 * modify, or manage its lifecycle.
 *
 * Implementations live in separate jars (itara-transport-http, etc.), are
 * created by {@link ItaraTransportFactory}
 * and discovered by the agent at startup via META-INF/itara/transport.
 * A single instance may serve multiple components (multiple registered
 * dispatchers) when their connections share the same grouping key.
 */
public interface ItaraTransport {

    /**
     * Send pre-serialized bytes to a remote component and return the
     * raw response bytes. The transport injects W3C trace headers from
     * the provided context but does not own context lifecycle.
     *
     * The timeout parameter is always passed by the proxy regardless of whether
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
     * @param headers      The headers collected for propagation
     * @param config       Connection properties from the wiring config
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
     * Called once per inbound connection during agent startup, before start().
     * The transport accumulates these registrations internally. Nothing is
     * started yet — the transport does not have the full picture until all
     * connections are processed.
     *
     * @param componentId  The id of the component being exposed
     * @param config       The parsed transport config for this connection
     * @param dispatcher   The dispatcher to call with raw request bytes
     */
    void registerListener(String componentId,
                          ItaraTransportConfig config,
                          DispatchHandler dispatcher);

    /**
     * Start the transport. Called once by the agent after all registerListener()
     * calls are complete. The transport now has the full picture and makes all
     * grouping and resource allocation decisions here — one server per port,
     * one consumer per group, etc.
     *
     * Must return only after the transport is ready to accept connections.
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
