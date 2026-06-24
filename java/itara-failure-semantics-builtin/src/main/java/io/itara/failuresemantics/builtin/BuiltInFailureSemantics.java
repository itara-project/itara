package io.itara.failuresemantics.builtin;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.failuresemantics.TransportCall;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/**
 * Built-in failure semantics backed by Resilience4j.
 *
 * Supports:
 *   - Fixed-delay retry with configurable attempt count and wait duration
 *   - Per-attempt timeout passed to the transport on every attempt
 *   - External timeout enforcement via ExecutorService when handleTimeout=true
 *   - Absolute timeout across all attempts
 *   - Idempotency guard — non-idempotent methods are not retried unless
 *     explicitly configured with retryNonIdempotent=true
 *   - Custom span per attempt via ObservabilityFacade.openCustomSpan(),
 *     making individual retry attempts visible as sibling spans in traces
 *
 * CHECKED and RUNTIME errors from the remote side are never retried —
 * they are passed through immediately. Only locally-originated TRANSPORT
 * errors (null serialized payload) are eligible for retry.
 *
 * One instance per connection. Thread-safe — the Retry instance and config
 * are immutable after construction.
 */
class BuiltInFailureSemantics implements ItaraFailureSemantics {

    private final BuiltInConfig config;
    private final Retry retry;

    BuiltInFailureSemantics(BuiltInConfig config) {
        this.config = config;
        this.retry  = buildRetry(config);
    }

    @Override
    public byte[] execute(TransportCall work, boolean idempotent)
            throws ItaraRemoteException {

        // Non-idempotent methods bypass retry entirely unless explicitly permitted.
        // A single attempt is still wrapped in a custom span for trace visibility.
        if (!idempotent && !config.retryNonIdempotent) {
            return executeAttempt(work, 1);
        }

        long absoluteDeadline = config.absoluteTimeout != null
                ? System.currentTimeMillis() + config.absoluteTimeout.toMillis()
                : Long.MAX_VALUE;

        int[] attemptNumber = {0};

        Callable<byte[]> retryable = Retry.decorateCallable(retry, () -> {
            int attempt = ++attemptNumber[0];

            if (System.currentTimeMillis() > absoluteDeadline) {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        TimeoutException.class.getName(),
                        "[Itara/built-in] Absolute timeout exceeded across all attempts");
            }
            return executeAttempt(work, attempt);
        });

        try {
            return retryable.call();
        } catch (ItaraRemoteException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof ItaraRemoteException) {
                throw (ItaraRemoteException) cause;
            }
            throw new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    e.getClass().getName(),
                    "[Itara/built-in] Unexpected failure: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a single attempt wrapped in a custom observability span.
     *
     * The span is opened before the transport call and closed after,
     * regardless of outcome. Because headers are built inside the
     * TransportCall lambda by the proxy, they are captured after this
     * span becomes the active context — so the callee's spans are
     * correctly parented under this retry attempt span (§14.7).
     */
    private byte[] executeAttempt(TransportCall work, int attempt)
            throws ItaraRemoteException {
        ObservabilityFacade facade = ObservabilityFacade.instance();
        try (ItaraScope span = facade.openCustomSpan(
                "attempt",
                Map.of("attempt", String.valueOf(attempt)))) {
            try {
                return work.call(config.timeout);
            } catch (ItaraRemoteException e) {
                span.setError(true);
                throw e;
            }
        }
    }

    private static Retry buildRetry(BuiltInConfig config) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(config.maxAttempts)
                .waitDuration(config.waitDuration)
                .retryOnException(e -> {
                    if (!(e instanceof ItaraRemoteException)) return false;
                    ItaraRemoteException ire = (ItaraRemoteException) e;
                    if (ire.getErrorKind() == ItaraRemoteException.ErrorKind.CHECKED) return false;
                    if (ire.getErrorKind() == ItaraRemoteException.ErrorKind.TRANSPORT) return true;
                    if (ire.getErrorKind() == ItaraRemoteException.ErrorKind.RUNTIME
                            && config.retryRuntime) return true;
                    return false;
                })
                .build();

        return Retry.of("itara-built-in", retryConfig);
    }
}
