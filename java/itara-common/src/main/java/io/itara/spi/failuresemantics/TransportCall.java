package io.itara.spi.failuresemantics;

import io.itara.exceptions.ItaraRemoteException;

import java.time.Duration;

/**
 * A single outbound transport call, ready to be executed.
 *
 * Passed to {@link ItaraFailureSemantics#execute} by the proxy. The failure
 * semantics implementation calls this once per attempt, supplying the
 * per-attempt timeout. The timeout is passed to the transport on every
 * invocation regardless of whether the transport acts on it (§14.10).
 *
 * Null may be passed as the timeout if none is configured — transports
 * treat null as no timeout enforced from this side.
 */
@FunctionalInterface
public interface TransportCall {

    /**
     * Execute the transport call.
     *
     * @param timeout  The per-attempt timeout to pass to the transport,
     *                 or null if no per-attempt timeout is configured.
     * @return         Raw response bytes from the transport.
     * @throws ItaraRemoteException on any transport or remote failure.
     */
    byte[] call(Duration timeout) throws ItaraRemoteException;
}
