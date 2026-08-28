package dev.itara.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ContextPropagation")
public class ContextPropagationTest {

    // ── toHeaders ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toHeaders")
    class ToHeaders {

        @Test
        @DisplayName("always includes traceId, spanId and requestId")
        void alwaysIncludesRequiredFields() {
            ItaraContext ctx = ItaraContext.newRoot("order-service");
            Map<String, String> headers = ContextPropagation.toHeaders(ctx);

            assertAll(
                    () -> assertNotNull(headers.get(ContextPropagation.HEADER_TRACE_ID)),
                    () -> assertNotNull(headers.get(ContextPropagation.HEADER_SPAN_ID)),
                    () -> assertNotNull(headers.get(ContextPropagation.HEADER_REQUEST_ID))
            );
        }

        @Test
        @DisplayName("omits correlationId when null")
        void omitsCorrelationIdWhenNull() {
            ItaraContext ctx = ItaraContext.newRoot("order-service");
            Map<String, String> headers = ContextPropagation.toHeaders(ctx);
            assertNull(headers.get(ContextPropagation.HEADER_CORRELATION));
        }

        @Test
        @DisplayName("includes correlationId when present")
        void includesCorrelationIdWhenPresent() {
            ItaraContext ctx = ItaraContext.newRoot("order-service", "order-123");
            Map<String, String> headers = ContextPropagation.toHeaders(ctx);
            assertEquals("order-123", headers.get(ContextPropagation.HEADER_CORRELATION));
        }

        @Test
        @DisplayName("omits edgePath when empty")
        void omitsEdgePathWhenEmpty() {
            ItaraContext ctx = ItaraContext.newRoot("order-service");
            Map<String, String> headers = ContextPropagation.toHeaders(ctx);
            assertNull(headers.get(ContextPropagation.HEADER_EDGE_PATH));
        }

        @Test
        @DisplayName("includes edgePath as comma-separated string when present")
        void includesEdgePathWhenPresent() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            Map<String, String> headers = ContextPropagation.toHeaders(child);
            assertEquals("calculator", headers.get(ContextPropagation.HEADER_EDGE_PATH));
        }
    }

    // ── fromHeaders — REQUEST_REPLY ───────────────────────────────────────────

    @Nested
    @DisplayName("fromHeaders — REQUEST_REPLY")
    class FromHeadersRequestReply {

        @Test
        @DisplayName("restores traceId and spanId from headers")
        void restoresTraceIdAndSpanId() {
            Map<String, String> headers = headersFor(ItaraContext.newRoot("gateway"));
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.REQUEST_REPLY);

            assertAll(
                    () -> assertNotNull(ctx.getItaraTraceId()),
                    () -> assertNotNull(ctx.getItaraSpanId())
            );
        }

        @Test
        @DisplayName("parentSpanId is null — not propagated across boundaries")
        void parentSpanIdIsNull() {
            Map<String, String> headers = headersFor(ItaraContext.newRoot("gateway"));
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.REQUEST_REPLY);
            assertNull(ctx.getItaraParentSpanId());
        }

        @Test
        @DisplayName("restores correlationId from headers")
        void restoresCorrelationId() {
            ItaraContext original = ItaraContext.newRoot("gateway", "order-123");
            Map<String, String> headers = headersFor(original);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.REQUEST_REPLY);
            assertEquals("order-123", ctx.getCorrelationId());
        }

        @Test
        @DisplayName("restores edgePath from headers")
        void restoresEdgePath() {
            ItaraContext root  = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            Map<String, String> headers = headersFor(child);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.REQUEST_REPLY);
            assertEquals(List.of("calculator"), ctx.getEdgePath());
        }

        @Test
        @DisplayName("returns root context when Itara headers are absent")
        void returnsRootContextWhenHeadersAbsent() {
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    Map.of(), ExchangePattern.REQUEST_REPLY);
            assertNotNull(ctx.getItaraTraceId());
            assertNotNull(ctx.getItaraSpanId());
            assertNull(ctx.getItaraParentSpanId());
        }
    }

    // ── fromHeaders — FIRE_AND_FORGET ─────────────────────────────────────────

    @Nested
    @DisplayName("fromHeaders — FIRE_AND_FORGET")
    class FromHeadersFireAndForget {

        @Test
        @DisplayName("preserves traceId from headers")
        void preservesTraceId() {
            ItaraContext original = ItaraContext.newRoot("order-producer");
            Map<String, String> headers = headersFor(original);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);
            assertEquals(original.getItaraTraceId(), ctx.getItaraTraceId());
        }

        @Test
        @DisplayName("generates fresh spanId — not inherited from producer")
        void generatesFreshSpanId() {
            ItaraContext original = ItaraContext.newRoot("order-producer");
            Map<String, String> headers = headersFor(original);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);
            assertNotEquals(original.getItaraSpanId(), ctx.getItaraSpanId());
        }

        @Test
        @DisplayName("parentSpanId is null — consumer is not a child of the producer")
        void parentSpanIdIsNull() {
            ItaraContext original = ItaraContext.newRoot("order-producer");
            Map<String, String> headers = headersFor(original);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);
            assertNull(ctx.getItaraParentSpanId());
        }

        @Test
        @DisplayName("edgePath is preserved from headers")
        void preservesEdgePath() {
            ItaraContext root  = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("order-producer");
            Map<String, String> headers = headersFor(child);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);
            assertEquals(List.of("order-producer"), ctx.getEdgePath());
        }

        @Test
        @DisplayName("restored spanId is null")
        void spanIdIsNull() {
            ItaraContext original = ItaraContext.newRoot("order-producer");
            Map<String, String> headers = headersFor(original);
            ItaraContext ctx = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);
            assertNull(ctx.getItaraSpanId());
        }
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("REQUEST_REPLY — toHeaders then fromHeaders preserves traceId, requestId, correlationId, edgePath")
        void requestReplyRoundTrip() {
            ItaraContext original = ItaraContext.newRoot("gateway", "corr-456");
            ItaraContext withPath = original.newChildSpan("order-service");
            Map<String, String> headers = ContextPropagation.toHeaders(withPath);
            ItaraContext restored = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.REQUEST_REPLY);

            assertAll(
                    () -> assertEquals(withPath.getItaraTraceId(), restored.getItaraTraceId()),
                    () -> assertEquals(withPath.getRequestId(),    restored.getRequestId()),
                    () -> assertEquals("corr-456",                  restored.getCorrelationId()),
                    () -> assertEquals(List.of("order-service"),    restored.getEdgePath()),
                    () -> assertNull(restored.getItaraParentSpanId())
            );
        }

        @Test
        @DisplayName("FIRE_AND_FORGET — toHeaders then fromHeaders preserves traceId, requestId, correlationId, edgePath; spanId is fresh")
        void fireAndForgetRoundTrip() {
            ItaraContext original = ItaraContext.newRoot("order-producer", "corr-789");
            ItaraContext withPath = original.newChildSpan("order-events/order-placed");
            Map<String, String> headers = ContextPropagation.toHeaders(withPath);
            ItaraContext restored = ContextPropagation.fromHeaders(
                    headers, ExchangePattern.FIRE_AND_FORGET);

            assertAll(
                    () -> assertEquals(withPath.getItaraTraceId(),                restored.getItaraTraceId()),
                    () -> assertEquals(withPath.getRequestId(),                    restored.getRequestId()),
                    () -> assertEquals("corr-789",                                  restored.getCorrelationId()),
                    () -> assertEquals(List.of("order-events/order-placed"),        restored.getEdgePath()),
                    () -> assertNull(restored.getItaraParentSpanId()),
                    () -> assertNotEquals(withPath.getItaraSpanId(),               restored.getItaraSpanId())
            );
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> headersFor(ItaraContext ctx) {
        return new HashMap<>(ContextPropagation.toHeaders(ctx));
    }
}
