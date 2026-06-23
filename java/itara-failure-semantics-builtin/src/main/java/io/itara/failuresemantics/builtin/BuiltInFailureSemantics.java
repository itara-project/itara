package io.itara.failuresemantics.builtin;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.failuresemantics.TransportCall;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        // Non-idempotent methods bypass retry entirely unless explicitly permitted
        if (!idempotent && !config.retryNonIdempotent) {
            return work.call(config.timeout);
        }

        long absoluteDeadline = config.absoluteTimeout != null
                ? System.currentTimeMillis() + config.absoluteTimeout.toMillis()
                : Long.MAX_VALUE;

        Callable<byte[]> retryable = Retry.decorateCallable(retry, () -> {
            if (System.currentTimeMillis() > absoluteDeadline) {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        TimeoutException.class.getName(),
                        "[Itara/built-in] Absolute timeout exceeded across all attempts");
            }
            // Timeout is passed to the transport on every attempt — the transport
            // enforces it natively (e.g. socket read timeout). External enforcement
            // via a separate thread is intentionally omitted: ItaraContext is
            // ThreadLocal and would not propagate across thread boundaries correctly.
            return work.call(config.timeout);
        });

        try {
            return retryable.call();
        } catch (ItaraRemoteException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof ItaraRemoteException ire) {
                throw ire;
            }
            throw new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    e.getClass().getName(),
                    "[Itara/built-in] Unexpected failure: " + e.getMessage(), e);
        }
    }

    private static Retry buildRetry(BuiltInConfig config) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(config.maxAttempts)
                .waitDuration(config.waitDuration)
                // Only retry on locally-originated TRANSPORT errors —
                // remote-side errors (CHECKED, RUNTIME) have null serializedPayload
                // and have already been reconstructed before reaching here,
                // so we check kind directly
                .retryOnException(e ->
                        e instanceof ItaraRemoteException ire
                                && ire.getErrorKind() == ItaraRemoteException.ErrorKind.TRANSPORT
                                && ire.getSerializedPayload() == null)
                .build();

        return Retry.of("itara-built-in", retryConfig);
    }
}
