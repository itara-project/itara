package io.itara.spi;

import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraContext;

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
 * Implementations live in separate jars (itara-transport-http, etc.) and are
 * discovered by the agent at startup via META-INF/itara/transport.
 */
public interface ItaraTransport {

    /**
     * The connection type string this transport handles.
     * Must match the 'type' field in the wiring config.
     * Examples: "http", "jms", "kafka"
     */
    String type();

    /**
     * Send pre-serialized bytes to a remote component and return the
     * raw response bytes. The transport injects W3C trace headers from
     * the provided context but does not own context lifecycle.
     *
     * The timeout parameter is always passed by the proxy regardless of whether
     * the transport acts on it. Transports that do not support timeout enforcement
     * MUST silently ignore it. Null means no per-attempt timeout is configured (§14.10)
     *
     * @param componentId  The id of the remote component
     * @param methodName   The method being called
     * @param payload      Pre-serialized argument bytes
     * @param headers      The headers collected for propagation
     * @param properties   Connection properties from the wiring config
     * @param timeout      The timeout value of the transport
     * @return             Raw response bytes
     * @throws Exception   On any transport-level failure
     */
    byte[] send(String componentId,
                String methodName,
                byte[] payload,
                Map<String, String> headers,
                Map<String, String> properties,
                Duration timeout) throws Exception;

    /**
     * Start a listener that receives inbound calls for the given component.
     * The listener delivers raw request bytes to the dispatcher and writes
     * raw response bytes back. It knows nothing about serialization or
     * observability.
     *
     * Must return immediately after the listener is ready to accept connections.
     *
     * @param componentId  The id of the component being exposed
     * @param properties   Connection properties from the wiring config
     * @param dispatcher   The dispatcher to call with raw request bytes
     */
    void startListener(String componentId,
                       Map<String, String> properties,
                       DispatchHandler dispatcher);

    /**
     * Stop the listener. Called by the agent's shutdown hook.
     * No-op by default.
     */
    default void stopListener() {}
}
