package topos.spi;

import topos.runtime.ToposRegistry;

import java.util.Map;

/**
 * Service Provider Interface for Topos transports.
 *
 * A transport is responsible for two things:
 *   1. Creating a proxy object that the caller uses to reach a remote component
 *   2. Starting a listener that receives inbound calls and dispatches them
 *      to the local component implementation via the registry
 *
 * Implementations live in separate jars (topos-transport-http,
 * topos-transport-jms, topos-transport-kafka, etc.) and are discovered
 * by the agent at startup via META-INF/topos/transport on the classpath.
 *
 * The transport type string (e.g. "http", "jms", "kafka") must match
 * the type field in the wiring config connection entries.
 */
public interface ToposTransport {

    /**
     * The connection type string this transport handles.
     * Must match the 'type' field in the wiring config.
     * Examples: "http", "jms", "kafka"
     */
    String type();

    /**
     * Create a proxy object that implements the given contract class
     * and routes calls to the remote component via this transport.
     *
     * Called by the agent for each outbound connection of this transport type.
     * The returned object is pre-registered in the ToposRegistry before
     * any activator runs.
     *
     * @param componentId  The id of the remote component (matches @ComponentInterface id)
     * @param contractClass The contract abstract class to proxy
     * @param properties   Connection-specific properties from the wiring config
     *                     (e.g. host, port, queue name, topic, etc.)
     * @param classLoader  The classloader to define the generated proxy class in
     * @return             A proxy instance implementing contractClass
     */
    Object createProxy(String componentId,
                       Class<?> contractClass,
                       Map<String, String> properties,
                       ClassLoader classLoader);

    /**
     * Start a listener that receives inbound calls for the given component
     * and dispatches them to the implementation via the registry.
     *
     * Called by the agent for each inbound connection of this transport type.
     * The listener must be started asynchronously — this method returns
     * immediately after the listener is ready to accept connections.
     *
     * @param componentId  The id of the component being exposed
     * @param properties   Connection-specific properties (port, queue name, etc.)
     * @param registry     The registry to retrieve the component instance from
     */
    void startListener(String componentId,
                       Map<String, String> properties,
                       ToposRegistry registry);

    /**
     * Stop the listener started by startListener(), if any.
     * Called by the agent's shutdown hook.
     * Implementations that have no listener may leave this as a no-op.
     */
    default void stopListener() {}
}
