package io.itara.spi.failuresemantics;

import io.itara.exceptions.ItaraRemoteException;

/**
 * Strategy for executing a remote component call with failure handling.
 *
 * One instance is created per connection at startup by the connection's
 * configured {@link ItaraFailureSemanticsFactory}. Configuration parsing
 * and validation happen at construction time — a malformed or missing
 * required parameter MUST cause startup to fail.
 *
 * The implementation owns retry, timeout, and circuit breaking decisions.
 * It receives the transport call as an opaque {@link TransportCall} and
 * may invoke it more than once. It knows nothing about serialization,
 * observability, or the registry.
 *
 * The implementation MUST NOT emit the four key observability events
 * (CALL_SENT, CALL_RECEIVED, RETURN_SENT, RETURN_RECEIVED) — those are
 * exclusively owned by the proxy and dispatcher (§14.5). It MAY emit
 * custom spans via the observer SPI (§9.7).
 *
 * Failure semantics do not apply to direct (colocated) connections (§14.9).
 */
public interface ItaraFailureSemantics {

    /**
     * Execute the unit of work according to this instance's strategy.
     *
     * Called by the proxy once per outbound remote invocation. The
     * implementation MAY invoke {@code work} more than once, but MUST NOT
     * retry if {@code idempotent} is false unless explicitly configured
     * to permit retrying non-idempotent methods (§14.5).
     *
     * CHECKED errors from the unit of work signal that the callee executed
     * and rejected the request through its declared error path. They MUST
     * be passed through immediately without retry (§14.12).
     *
     * On exhaustion of all attempts or timeout, the implementation MUST
     * throw {@link ItaraRemoteException} with kind TRANSPORT.
     * Library-specific exceptions MUST NOT be exposed to calling code (§14.12).
     *
     * @param work       The transport call to execute. May be invoked
     *                   more than once if the strategy permits.
     * @param idempotent True if the method being called is idempotent.
     * @return           Raw response bytes from the transport.
     * @throws ItaraRemoteException on failure. Kind is TRANSPORT if all
     *                   attempts are exhausted or a timeout is exceeded.
     *                   CHECKED errors from the unit of work are passed
     *                   through as-is.
     */
    byte[] execute(TransportCall work, boolean idempotent) throws ItaraRemoteException;
}
