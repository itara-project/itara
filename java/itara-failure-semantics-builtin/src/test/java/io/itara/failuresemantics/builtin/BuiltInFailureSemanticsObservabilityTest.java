package io.itara.failuresemantics.builtin;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ItaraContext;
import io.itara.runtime.ItaraObserver;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.ObserverRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that BuiltInFailureSemantics emits correct custom spans
 * via ObservabilityFacade on each attempt.
 *
 * Uses a capturing test observer registered against the singleton
 * ObserverRegistry, cleaned up after each test.
 */
@DisplayName("BuiltInFailureSemantics — observability")
public class BuiltInFailureSemanticsObservabilityTest {

    private CapturingObserver observer;

    @BeforeEach
    void setUp() {
        ObservabilityFacade.initialize();
        observer = new CapturingObserver();
        ObserverRegistry.instance().register(observer);
        // Push a root context — openCustomSpan expects an active context
        ItaraContext.push(ItaraContext.newRoot("test"));
    }

    @AfterEach
    void tearDown() {
        ItaraContext.clear();
        ObserverRegistry.instance().resetForTest();
        ObservabilityFacade.resetForTest();
    }

    private BuiltInFailureSemantics build(int maxAttempts, boolean retryNonIdempotent) {
        return new BuiltInFailureSemantics(new BuiltInConfig(
                maxAttempts,
                Duration.ofMillis(1),
                null,
                false,
                null,
                retryNonIdempotent
        ));
    }

    @Nested
    @DisplayName("successful call")
    class SuccessfulCall {

        @Test
        @DisplayName("single successful attempt emits one open and one close")
        void singleAttemptEmitsOneSpan() throws ItaraRemoteException {
            BuiltInFailureSemantics fs = build(3, false);

            fs.execute(timeout -> new byte[0], true);

            assertEquals(1, observer.opened.size());
            assertEquals(1, observer.closed.size());
        }

        @Test
        @DisplayName("span name is 'attempt'")
        void spanNameIsAttempt() throws ItaraRemoteException {
            BuiltInFailureSemantics fs = build(3, false);

            fs.execute(timeout -> new byte[0], true);

            assertEquals("attempt", observer.opened.get(0).getName());
        }

        @Test
        @DisplayName("attempt number is set as attribute")
        void attemptAttributeSet() throws ItaraRemoteException {
            BuiltInFailureSemantics fs = build(3, false);

            fs.execute(timeout -> new byte[0], true);

            assertEquals("1", observer.opened.get(0).getAttributes().get("attempt"));
        }

        @Test
        @DisplayName("span closes without error on success")
        void spanClosesWithoutError() throws ItaraRemoteException {
            BuiltInFailureSemantics fs = build(3, false);

            fs.execute(timeout -> new byte[0], true);

            assertFalse(observer.closed.get(0).isError());
        }
    }

    @Nested
    @DisplayName("retry attempts")
    class RetryAttempts {

        @Test
        @DisplayName("each retry attempt emits its own span")
        void eachAttemptEmitsSpan() {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false);

            assertThrows(ItaraRemoteException.class, () ->
                    fs.execute(timeout -> {
                        attempts.incrementAndGet();
                        throw transportFailure();
                    }, true));

            assertEquals(3, observer.opened.size());
            assertEquals(3, observer.closed.size());
        }

        @Test
        @DisplayName("attempt numbers increment across retries")
        void attemptNumbersIncrement() {
            BuiltInFailureSemantics fs = build(3, false);

            assertThrows(ItaraRemoteException.class, () ->
                    fs.execute(timeout -> { throw transportFailure(); }, true));

            assertEquals("1", observer.opened.get(0).getAttributes().get("attempt"));
            assertEquals("2", observer.opened.get(1).getAttributes().get("attempt"));
            assertEquals("3", observer.opened.get(2).getAttributes().get("attempt"));
        }

        @Test
        @DisplayName("failed attempts close with error=true")
        void failedAttemptsCloseWithError() {
            BuiltInFailureSemantics fs = build(3, false);

            assertThrows(ItaraRemoteException.class, () ->
                    fs.execute(timeout -> { throw transportFailure(); }, true));

            assertTrue(observer.closed.stream().allMatch(SpanClose::isError),
                    "All failed attempt spans must close with error=true");
        }

        @Test
        @DisplayName("successful retry closes final span without error")
        void successfulRetryClosesWithoutError() throws ItaraRemoteException {
            AtomicInteger attempts = new AtomicInteger(0);
            BuiltInFailureSemantics fs = build(3, false);

            fs.execute(timeout -> {
                if (attempts.incrementAndGet() < 3) throw transportFailure();
                return new byte[0];
            }, true);

            assertEquals(3, observer.closed.size());
            assertFalse(observer.closed.get(2).isError(),
                    "Final successful attempt must close without error");
            assertTrue(observer.closed.get(0).isError(),
                    "First failed attempt must close with error");
        }
    }

    @Nested
    @DisplayName("non-idempotent bypass")
    class NonIdempotentBypass {

        @Test
        @DisplayName("non-idempotent single attempt still emits a span")
        void nonIdempotentEmitsSpan() {
            BuiltInFailureSemantics fs = build(3, false);

            assertThrows(ItaraRemoteException.class, () ->
                    fs.execute(timeout -> { throw transportFailure(); }, false));

            assertEquals(1, observer.opened.size(),
                    "Non-idempotent bypass must still emit a span");
        }

        @Test
        @DisplayName("non-idempotent span has attempt=1")
        void nonIdempotentSpanHasAttemptOne() {
            BuiltInFailureSemantics fs = build(3, false);

            assertThrows(ItaraRemoteException.class, () ->
                    fs.execute(timeout -> { throw transportFailure(); }, false));

            assertEquals("1", observer.opened.get(0).getAttributes().get("attempt"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ItaraRemoteException transportFailure() {
        return new ItaraRemoteException(
                ItaraRemoteException.ErrorKind.TRANSPORT,
                "java.net.ConnectException", "Connection refused");
    }

    static final class SpanOpen {
        final ItaraContext ctx;
        final String name;
        final Map<String, String> attributes;

        SpanOpen(ItaraContext ctx, String name, Map<String, String> attributes) {
            this.ctx        = ctx;
            this.name       = name;
            this.attributes = attributes;
        }

        String getName() { return name; }
        Map<String, String> getAttributes() {return attributes; }
    }

    static final class SpanClose {
        final ItaraContext ctx;
        final String name;
        final boolean error;

        SpanClose(ItaraContext ctx, String name, boolean error) {
            this.ctx   = ctx;
            this.name  = name;
            this.error = error;
        }

        String getName() { return name; }
        boolean isError() { return error; }
    }

    static class CapturingObserver implements ItaraObserver {
        final List<SpanOpen>  opened = new ArrayList<>();
        final List<SpanClose> closed = new ArrayList<>();

        @Override
        public void onCustomSpan(ItaraContext ctx, String name,
                                 Map<String, String> attributes, long timestamp) {
            opened.add(new SpanOpen(ctx, name, attributes));
        }

        @Override
        public void onCustomSpanClosed(ItaraContext ctx, String name,
                                       long timestamp, boolean error) {
            closed.add(new SpanClose(ctx, name, error));
        }
    }
}
