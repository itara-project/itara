package dev.itara.observability.logging;

import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LoggingObserver")
public class LoggingObserverTest {

    private LoggingObserver observer;
    private CapturingHandler handler;

    @BeforeEach
    void setUp() {
        observer = new LoggingObserver();
        handler = new CapturingHandler();
        Logger.getLogger(LoggingObserver.class.getName()).addHandler(handler);
    }

    @Nested
    @DisplayName("core events")
    class CoreEvents {

        @Test
        @DisplayName("CALL_SENT logs component and method")
        void callSentLogs() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http",
                    ExchangePattern.REQUEST_REPLY, System.nanoTime());

            assertTrue(handler.lastMessage().contains("CALL_SENT"));
            assertTrue(handler.lastMessage().contains("calculator.add"));
        }

        @Test
        @DisplayName("CALL_RECEIVED logs component and method")
        void callReceivedLogs() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallReceived(ctx, "calculator", "add", "http",
                    ExchangePattern.REQUEST_REPLY, System.nanoTime());

            assertTrue(handler.lastMessage().contains("CALL_RECEIVED"));
            assertTrue(handler.lastMessage().contains("calculator.add"));
        }

        @Test
        @DisplayName("RETURN_SENT logs execution duration")
        void returnSentLogsDuration() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            long start = System.nanoTime();
            observer.onCallReceived(ctx, "calculator", "add", "http",
                    ExchangePattern.REQUEST_REPLY, start);
            observer.onReturnSent(ctx, "calculator", "add",
                    start + 1_000_000L, false);

            assertTrue(handler.lastMessage().contains("RETURN_SENT"));
            assertTrue(handler.lastMessage().contains("execution="));
        }

        @Test
        @DisplayName("RETURN_RECEIVED logs total latency")
        void returnReceivedLogsLatency() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            long start = System.nanoTime();
            observer.onCallSent(ctx, "calculator", "add", "http",
                    ExchangePattern.REQUEST_REPLY, start);
            observer.onReturnReceived(ctx, "calculator", "add",
                    start + 2_000_000L, false);

            assertTrue(handler.lastMessage().contains("RETURN_RECEIVED"));
            assertTrue(handler.lastMessage().contains("latency="));
        }

        @Test
        @DisplayName("error flag is logged")
        void errorFlagLogged() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "divide", "http",
                    ExchangePattern.REQUEST_REPLY, System.nanoTime());
            observer.onReturnReceived(ctx, "calculator", "divide",
                    System.nanoTime(), true);

            assertTrue(handler.lastMessage().contains("ERROR"));
        }

        @Test
        @DisplayName("traceId and spanId are included in log output")
        void traceAndSpanIdLogged() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http",
                    ExchangePattern.REQUEST_REPLY, System.nanoTime());

            assertTrue(handler.lastMessage().contains(ctx.getItaraTraceId()));
            assertTrue(handler.lastMessage().contains(ctx.getItaraSpanId()));
        }

        @Test
        @DisplayName("null context is handled gracefully")
        void nullContextHandledGracefully() {
            assertDoesNotThrow(() ->
                    observer.onCallSent(null, "calculator", "add", "http",
                            ExchangePattern.REQUEST_REPLY, System.nanoTime()));
        }
    }

    @Nested
    @DisplayName("custom spans")
    class CustomSpans {

        @Test
        @DisplayName("CUSTOM_SPAN logs name and attributes")
        void customSpanLogs() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCustomSpan(ctx, "attempt",
                    Map.of("attempt", "1"), System.nanoTime());

            assertTrue(handler.lastMessage().contains("CUSTOM_SPAN"));
            assertTrue(handler.lastMessage().contains("attempt"));
        }

        @Test
        @DisplayName("CUSTOM_SPAN_CLOSED logs duration")
        void customSpanClosedLogsDuration() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            long start = System.nanoTime();
            observer.onCustomSpan(ctx, "attempt",
                    Map.of("attempt", "1"), start);
            observer.onCustomSpanClosed(ctx, "attempt",
                    start + 500_000L, false);

            assertTrue(handler.lastMessage().contains("CUSTOM_SPAN_CLOSED"));
            assertTrue(handler.lastMessage().contains("duration="));
        }

        @Test
        @DisplayName("CUSTOM_SPAN_CLOSED logs error flag")
        void customSpanClosedLogsError() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCustomSpan(ctx, "attempt",
                    Map.of("attempt", "1"), System.nanoTime());
            observer.onCustomSpanClosed(ctx, "attempt",
                    System.nanoTime(), true);

            assertTrue(handler.lastMessage().contains("ERROR"));
        }

        @Test
        @DisplayName("empty attributes map does not cause errors")
        void emptyAttributesHandled() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            assertDoesNotThrow(() ->
                    observer.onCustomSpan(ctx, "attempt",
                            Map.of(), System.nanoTime()));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    static class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override public void flush() {}
        @Override public void close() {}

        String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }
    }
}
