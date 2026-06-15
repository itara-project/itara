package io.itara.runtime;

import java.util.Map;

/**
 * Callback interface passed to transport listeners.
 *
 * The transport calls dispatch() with raw request bytes and writes the
 * returned raw response bytes back to the caller. The transport knows nothing
 * about what those bytes contain.
 *
 * The implementation — owned by the agent — handles the full inbound pipeline:
 * observability, deserialization, component invocation, result serialization.
 *
 * A DispatchHandler is constructed once per inbound connection at startup,
 * with all dependencies (serializer, registry, observability facade, contract
 * metadata) wired in. Nothing is looked up at call time.
 */
public interface DispatchHandler {

    /**
     * Dispatch a raw inbound call to the component implementation.
     *
     * @param componentId   The target component id, parsed from the request
     * @param methodName    The target method name, parsed from the request
     * @param requestBytes  Raw serialized argument bytes from the transport
     * @return              Raw serialized response bytes for the transport to send back
     * @throws Exception    On any dispatch failure — transport maps this to an error response
     */
    byte[] dispatch(String componentId, String methodName, byte[] requestBytes, Map<String, String> headers) throws Exception;
}
