package dev.itara.agent.failuresemantics;

import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.spi.failuresemantics.TransportCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NoopFailureSemantics")
public class NoopFailureSemanticsTest {

    private NoopFailureSemantics noop;

    @BeforeEach
    void setUp() {
        noop = new NoopFailureSemantics();
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("returns result from the unit of work on success")
        void returnsResultOnSuccess() throws ItaraRemoteException {
            byte[] expected = {1, 2, 3};
            TransportCall work = timeout -> expected;

            byte[] result = noop.execute(work, true);

            assertArrayEquals(expected, result);
        }

        @Test
        @DisplayName("passes null timeout to the transport")
        void passesNullTimeout() throws ItaraRemoteException {
            Duration[] capturedTimeout = {Duration.ofSeconds(99)}; // sentinel
            TransportCall work = timeout -> {
                capturedTimeout[0] = timeout;
                return new byte[0];
            };

            noop.execute(work, true);

            assertNull(capturedTimeout[0]);
        }

        @Test
        @DisplayName("surfaces error immediately without retry")
        void surfacesErrorImmediately() {
            int[] callCount = {0};
            TransportCall work = timeout -> {
                callCount[0]++;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        "java.net.ConnectException",
                        "Connection refused");
            };

            assertThrows(ItaraRemoteException.class, () -> noop.execute(work, true));
            assertEquals(1, callCount[0], "Must not retry — noop invokes the unit of work exactly once");
        }

        @Test
        @DisplayName("idempotency flag has no effect — never retries regardless")
        void idempotencyFlagHasNoEffect() {
            int[] callCount = {0};
            TransportCall work = timeout -> {
                callCount[0]++;
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.TRANSPORT,
                        "java.net.ConnectException",
                        "Connection refused");
            };

            assertThrows(ItaraRemoteException.class, () -> noop.execute(work, false));
            assertEquals(1, callCount[0], "Non-idempotent call also invoked exactly once");
        }

        @Test
        @DisplayName("propagates ItaraRemoteException kind unchanged")
        void propagatesErrorKindUnchanged() {
            TransportCall work = timeout -> {
                throw new ItaraRemoteException(
                        ItaraRemoteException.ErrorKind.CHECKED,
                        "com.example.SomeException",
                        "contract violation");
            };

            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class, () -> noop.execute(work, true));
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind());
        }
    }
}
