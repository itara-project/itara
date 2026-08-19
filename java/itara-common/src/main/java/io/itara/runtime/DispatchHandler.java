package io.itara.runtime;

import io.itara.spi.identity.ItaraTransportCredential;

import java.util.Map;

/**
 * Callback interface passed to transport listeners.
 *
 * The transport calls dispatch() with raw request bytes and writes the
 * returned raw response bytes back to the caller. The transport knows nothing
 * about what those bytes contain, and — as of authentication/authorization
 * (§15, §16) — nothing about which component or method is being called
 * either. That identification travels via headers (CallTargetPropagation),
 * reconstructed by the dispatcher itself, not supplied by the transport.
 *
 * The implementation — owned by the agent — handles the full inbound pipeline:
 * observability, authentication, authorization, deserialization, component
 * invocation, result serialization.
 *
 * A DispatchHandler is constructed once per inbound connection at startup,
 * with all dependencies (serializer, registry, observability facade, contract
 * metadata) wired in. Nothing is looked up at call time.
 *
 * getDispatchKey() identifies which declared connection this handler serves.
 * A transport uses it to select the correct handler among
 * several that might otherwise be ambiguous by component id alone — the
 * transport keys its own listener storage by this value rather than by a
 * string it is separately handed.
 */
public interface DispatchHandler {

    /**
     * The connection this handler serves. Fixed at construction — never
     * changes for the lifetime of this handler.
     */
    String getDispatchKey();

    /**
     * Dispatch a raw inbound call to the component implementation.
     *
     * @param requestBytes        Raw serialized argument bytes from the transport
     * @param headers             The inbound headers — carries the caller-declared
     *                            call target, any authentication assertion, and
     *                            everything else riding along
     * @param transportCredential A connection-level credential the transport
     *                            itself terminated and surfaced (e.g. a TLS
     *                            peer certificate), or null if it has nothing
     *                            to surface
     * @return                    Raw serialized response bytes for the transport to send back
     * @throws Exception          On any dispatch failure — transport maps this to an error response
     */
    byte[] dispatch(byte[] requestBytes, Map<String, String> headers, ItaraTransportCredential transportCredential) throws Exception;
}
