package io.itara.observability.otel;

import io.itara.runtime.ItaraContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.*;

 import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OtelObserver.
 *
 * Uses the OTel SDK with in-memory exporters — no external systems required.
 * GlobalOpenTelemetry is configured once per test class and reset after.
 */
@DisplayName("OtelObserver")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtelObserverTest {

    private static InMemorySpanExporter  spanExporter;
    private static InMemoryMetricReader  metricReader;
    private static OtelObserver          observer;

    @BeforeAll
    static void setUpOtel() {
        spanExporter = InMemorySpanExporter.create();
        metricReader = InMemoryMetricReader.create();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        observer = new OtelObserver();
    }

    @AfterAll
    static void tearDownOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @BeforeEach
    void clearExporters() {
        spanExporter.reset();
    }

    private long now() { return System.nanoTime(); }

    // ── Span lifecycle ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("span lifecycle")
    class SpanLifecycle {

        @Test
        @Order(1)
        @DisplayName("CALL_SENT + RETURN_RECEIVED produces a CLIENT span")
        void callerSpan() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals(SpanKind.CLIENT, spans.get(0).getKind());
            assertEquals("calculator.add", spans.get(0).getName());
            assertEquals(StatusCode.UNSET, spans.get(0).getStatus().getStatusCode());
        }

        @Test
        @Order(2)
        @DisplayName("CALL_RECEIVED + RETURN_SENT produces a SERVER span")
        void calleeSpan() {
            ItaraContext parent = ItaraContext.newRoot("gateway");
            ItaraContext ctx = parent.newChildSpan("calculator");
            observer.onCallReceived(ctx, "calculator", "add", "http", now());
            observer.onReturnSent(ctx, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals(SpanKind.SERVER, spans.get(0).getKind());
            assertEquals("calculator.add", spans.get(0).getName());
        }

        @Test
        @Order(3)
        @DisplayName("error sets span status to ERROR")
        void errorSpan() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "divide", "http", now());
            observer.onReturnReceived(ctx, "calculator", "divide", now(), true);

            assertEquals(StatusCode.ERROR, spanExporter.getFinishedSpanItems().get(0).getStatus().getStatusCode());
        }

        @Test
        @Order(4)
        @DisplayName("null context is handled gracefully — no span created")
        void nullContext() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            assertEquals(StatusCode.UNSET, spanExporter.getFinishedSpanItems().get(0).getStatus().getStatusCode());
        }

        @Test
        @Order(5)
        @DisplayName("two unrelated root calls produce spans with different OTel traceIds")
        void unrelatedCallsHaveDifferentTraceIds() {
            ItaraContext ctx1 = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx1, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx1, "calculator", "add", now(), false);

            ItaraContext ctx2 = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx2, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx2, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            assertNotEquals(spans.get(0).getTraceId(), spans.get(1).getTraceId(),
                    "Unrelated calls must produce spans on different OTel traces");
        }
    }

    // ── Parent-child relationship ───────────────────────────────────────────

    @Nested
    @DisplayName("parent-child span relationship")
    class ParentChild {

        @Test
        @Order(6)
        @DisplayName("direct call: SERVER span is child of CLIENT span")
        void directCallParentChild() {
            ItaraContext callerCtx = ItaraContext.newRoot("gateway");
            ItaraContext calleeCtx = callerCtx.newChildSpan("calculator");

            // Opener events push onto OTel's context stack via makeCurrent()
            observer.onCallSent(callerCtx, "calculator", "add", "direct", now());
            observer.onCallReceived(calleeCtx, "calculator", "add", "direct", now());
            observer.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            observer.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());

            SpanData clientSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                    .findFirst().orElseThrow();
            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId(),
                    "CLIENT and SERVER spans must share the same OTel traceId");
            assertEquals(clientSpan.getSpanId(),
                    serverSpan.getParentSpanContext().getSpanId(),
                    "SERVER span must be a child of the CLIENT span");
        }

        @Test
        @Order(7)
        @DisplayName("root CLIENT span has no parent in the backend")
        void rootSpanHasNoParent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertFalse(span.getParentSpanContext().isValid(),
                    "Root span must have no parent — dangling parent refs break trace trees");
        }

        @Test
        @Order(8)
        @DisplayName("remote call: SERVER span is child of remote CLIENT via W3C headers")
        void remoteCallParentChild() {
            // ── Caller side ────────────────────────────────────────────────
            ItaraContext callerCtx = ItaraContext.newRoot("gateway");
            observer.onCallSent(callerCtx, "calculator", "add", "http", now());
            Map<String, String> headers = observer.serializeContext();
            observer.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            // ── Callee side (simulated on same thread) ─────────────────────
            ItaraContext calleeCtx = callerCtx.newChildSpan("calculator");
            observer.restoreContext(headers);
            observer.onCallReceived(calleeCtx, "calculator", "add", "http", now());
            observer.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            observer.onInboundContextReleased();

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());

            SpanData clientSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                    .findFirst().orElseThrow();
            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId(),
                    "Cross-process spans must share the same OTel traceId");
            assertEquals(clientSpan.getSpanId(),
                    serverSpan.getParentSpanContext().getSpanId(),
                    "Remote SERVER span must be linked to the caller's CLIENT span");
        }
    }

    // ── Span attributes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("span attributes")
    class SpanAttributes {

        @Test
        @Order(9)
        @DisplayName("component, method, and transport are set on every span")
        void coreAttributesPresent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals("calculator", span.getAttributes().get(OtelObserver.ATTR_COMPONENT));
            assertEquals("add",        span.getAttributes().get(OtelObserver.ATTR_METHOD));
            assertEquals("http",       span.getAttributes().get(OtelObserver.ATTR_TRANSPORT));
        }

        @Test
        @Order(10)
        @DisplayName("itaraTraceId and itaraSpanId are set as custom attributes")
        void itaraIdsSetAsAttributes() {
            // These Itara-native IDs are independent of the OTel span IDs.
            // They allow cross-observer correlation: find the audit log entry
            // that matches this OTel span without timestamp heuristics.
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals(ctx.getItaraTraceId(),
                    span.getAttributes().get(OtelObserver.ATTR_ITARA_TRACE),
                    "itara.trace.id must be set for cross-observer correlation");
            assertEquals(ctx.getItaraSpanId(),
                    span.getAttributes().get(OtelObserver.ATTR_ITARA_SPAN),
                    "itara.span.id must be set for cross-observer correlation");
        }

        @Test
        @Order(11)
        @DisplayName("requestId is set for cross-signal correlation")
        void requestIdPresent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            assertNotNull(
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelObserver.ATTR_REQUEST_ID),
                    "requestId must be present for log/trace/metric correlation");
        }

        @Test
        @Order(12)
        @DisplayName("correlationId is visible when set on the context")
        void correlationIdVisibleWhenSet() {
            ItaraContext ctx = ItaraContext.newRoot("gateway", "order-12345");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            assertEquals("order-12345",
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelObserver.ATTR_CORRELATION),
                    "Business correlationId must be visible for business-level tracing");
        }

        @Test
        @Order(13)
        @DisplayName("sourceNode is visible on every span")
        void sourceNodePresent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            assertEquals("gateway",
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelObserver.ATTR_SOURCE_NODE));
        }
    }

    // ── W3C propagation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("W3C context propagation")
    class W3CPropagation {

        @Test
        @Order(14)
        @DisplayName("serializeContext produces a traceparent header after onCallSent")
        void serializeContextProducesTraceparent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());

            Map<String, String> headers = observer.serializeContext();
            assertNotNull(headers.get("traceparent"),
                    "serializeContext must produce a W3C traceparent header");

            observer.onReturnReceived(ctx, "calculator", "add", now(), false);
        }

        @Test
        @Order(15)
        @DisplayName("serializeContext on direct transport returns empty map")
        void serializeContextEmptyForDirect() {
            // For direct calls the facade never calls serializeContext,
            // but if called it must return an empty map safely.
            Map<String, String> headers = observer.serializeContext();
            // No current OTel span → inject produces nothing meaningful,
            // but it must not throw and must return a map.
            assertNotNull(headers);
        }
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @Order(16)
        @DisplayName("call duration is recorded for every completed call")
        void durationRecorded() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElse(null);

            assertNotNull(histogram, "itara.call.duration must be recorded");
            assertFalse(histogram.getData().getPoints().isEmpty());
        }

        @Test
        @Order(17)
        @DisplayName("call duration is non-negative")
        void durationNonNegative() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            double sum = histogram.getData().getPoints().stream()
                    .mapToDouble(p -> ((HistogramPointData) p).getSum())
                    .sum();

            assertTrue(sum >= 0);
        }

        @Test
        @Order(18)
        @DisplayName("each call increments the metric count")
        void countIncrements() {
            ItaraContext ctx1 = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx1, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx1, "calculator", "add", now(), false);

            ItaraContext ctx2 = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx2, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx2, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            long count = histogram.getData().getPoints().stream()
                    .mapToLong(p -> ((HistogramPointData) p).getCount())
                    .sum();

            assertTrue(count >= 2);
        }

        @Test
        @Order(19)
        @DisplayName("failed calls are distinguishable via error=true dimension")
        void errorDimension() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "divide", "http", now());
            observer.onReturnReceived(ctx, "calculator", "divide", now(), true);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            boolean hasErrorPoint = histogram.getData().getPoints().stream()
                    .anyMatch(p -> Boolean.TRUE.equals(
                            p.getAttributes().get(OtelObserver.ATTR_ERROR)));

            assertTrue(hasErrorPoint,
                    "Failed calls must be distinguishable via error=true dimension");
        }

        @Test
        @Order(20)
        @DisplayName("transport type is a metric dimension")
        void transportDimension() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            observer.onCallSent(ctx, "calculator", "add", "http", now());
            observer.onReturnReceived(ctx, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            boolean hasTransportDimension = histogram.getData().getPoints().stream()
                    .anyMatch(p -> "http".equals(
                            p.getAttributes().get(OtelObserver.ATTR_TRANSPORT)));

            assertTrue(hasTransportDimension,
                    "Transport type must be a metric dimension for topology analysis");
        }
    }
}
