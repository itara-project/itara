package io.itara.failuresemantics.builtin;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.spi.failuresemantics.TransportCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BuiltInFailureSemantics")
public class BuiltInFailureSemanticsTest {

    // Builds an instance with sensible test defaults
    private BuiltInFailureSemantics build(int maxAttempts,
                                          boolean retryNonIdempotent,
                                          Duration timeout,
                                          Duration absoluteTimeout) {
        return new BuiltInFailureSemantics(new BuiltInConfig(
                maxAttempts,
                Duration.ofMillis(1), // minimal wait so tests are fast
                timeout,
                false,
                absoluteTimeout,
                retryNonIdempotent
        ));
    }

    private BuiltInFailureSemantics buildDefault() {
        return build(3, false, null, null);
    }

    // Transport failure with no serialized payload — eligible for retry
    private static ItaraRemoteException transportFailure() {
        return new ItaraRemoteException(
                ItaraRemoteException.ErrorKind.TRANSPORT,
                "java.net.ConnectException",
                "Connection refused");
    }

    // Remote-side error with serialized payload — must not be retried
    private static ItaraRemoteException remoteError(ItaraRemoteException.ErrorKind kind) {
        ItaraRemoteException ex = new ItaraRemoteException(new byte[]{1});
        return ex; // kind is always TRANSPORT in this constructor but payload is non-null
    }

    @Nested
    @DisplayName("retry behaviour")
    class RetryBehaviour {

        @Test
        @DisplayName("returns result on first success")
        void returnsOnFirstSuccess() throws ItaraRemoteException {
            byte[] expected = {42};
            BuiltInFailureSemantics fs = buildDefault();

            byte[] result = fs.execute(timeout -> expected, true);

            assertArrayEquals(expected, result);
        }

        @Test
        @DisplayName("retries on TRANSPORT failure and returns result on subsequent success")
        void retriesAndReturnsOnSubsequentSuccess() throws ItaraRemoteException {
            AtomicInteger attempts = new AtomicInteger(0);
            byte[] expected = {99};
            BuiltInFailureSemantics fs = buildDefault();

            TransportCall work = timeout -> {
                if (attempts.incrementAndGet() < 3) throw transportFailure();
                return expected;
            };

            byte[] result = fs.execute(work, true);

            assertArrayEquals(expected, result);
            assertEquals(3, attempts.get());
        }

        @Test
        @DisplayName("throws TRANSPORT after all attempts exhausted")
        void throwsAfterAllAttemptsExhausted() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false, null, null);

            TransportCall work = timeout -> {
                attempts.incrementAndGet();
                throw transportFailure();
            };

            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class, () -> fs.execute(work, true));

            assertEquals(3, attempts.get(), "Should have attempted exactly maxAttempts times");
            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
        }

        @Test
        @DisplayName("does not retry more than maxAttempts")
        void doesNotExceedMaxAttempts() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(2, false, null, null);

            assertThrows(ItaraRemoteException.class,
                    () -> fs.execute(timeout -> { attempts.incrementAndGet(); throw transportFailure(); }, true));

            assertEquals(2, attempts.get());
        }
    }

    @Nested
    @DisplayName("idempotency guard")
    class IdempotencyGuard {

        @Test
        @DisplayName("does not retry non-idempotent method by default")
        void doesNotRetryNonIdempotentByDefault() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false, null, null);

            assertThrows(ItaraRemoteException.class,
                    () -> fs.execute(timeout -> { attempts.incrementAndGet(); throw transportFailure(); }, false));

            assertEquals(1, attempts.get(), "Non-idempotent — must not retry");
        }

        @Test
        @DisplayName("retries non-idempotent method when retryNonIdempotent=true")
        void retriesNonIdempotentWhenConfigured() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, true, null, null);

            assertThrows(ItaraRemoteException.class,
                    () -> fs.execute(timeout -> { attempts.incrementAndGet(); throw transportFailure(); }, false));

            assertEquals(3, attempts.get(), "retryNonIdempotent=true — should retry up to maxAttempts");
        }

        @Test
        @DisplayName("retries idempotent method regardless of retryNonIdempotent setting")
        void retriesIdempotentMethodAlways() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false, null, null);

            assertThrows(ItaraRemoteException.class,
                    () -> fs.execute(timeout -> { attempts.incrementAndGet(); throw transportFailure(); }, true));

            assertEquals(3, attempts.get());
        }
    }

    @Nested
    @DisplayName("error pass-through")
    class ErrorPassThrough {

        @Test
        @DisplayName("remote-side error is not retried — passes through immediately")
        void remoteSideErrorPassesThroughImmediately() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false, null, null);

            // Remote error — has non-null serialized payload
            TransportCall work = timeout -> {
                attempts.incrementAndGet();
                throw new ItaraRemoteException(new byte[]{1, 2, 3});
            };

            assertThrows(ItaraRemoteException.class, () -> fs.execute(work, true));
            assertEquals(1, attempts.get(), "Remote-side error must not be retried");
        }

        @Test
        @DisplayName("passes timeout to the transport on every attempt")
        void passesTimeoutOnEveryAttempt() throws ItaraRemoteException {
            Duration expected = Duration.ofSeconds(2);
            AtomicInteger attempts = new AtomicInteger(0);
            Duration[] lastSeen = {null};
            BuiltInFailureSemantics fs = build(3, false, expected, null);

            TransportCall work = timeout -> {
                lastSeen[0] = timeout;
                if (attempts.incrementAndGet() < 3) throw transportFailure();
                return new byte[0];
            };

            fs.execute(work, true);

            assertEquals(expected, lastSeen[0], "Timeout must be passed to the transport");
            assertEquals(3, attempts.get());
        }
    }

    @Nested
    @DisplayName("absolute timeout")
    class AbsoluteTimeout {

        @Test
        @DisplayName("throws TRANSPORT when absolute timeout is exceeded mid-retry")
        void throwsWhenAbsoluteTimeoutExceeded() {
            // Absolute timeout of 1ms — will be exceeded after the first slow attempt
            BuiltInFailureSemantics fs = build(5, false, null, Duration.ofMillis(1));

            TransportCall work = timeout -> {
                try {
                    Thread.sleep(10); // guaranteed to exceed 1ms absolute timeout
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                throw transportFailure();
            };

            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class, () -> fs.execute(work, true));

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
        }
    }
}
