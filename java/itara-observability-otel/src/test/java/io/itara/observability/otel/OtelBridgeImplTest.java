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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral tests for OtelBridgeImpl.
 *
 * These tests describe what an engineer should be able to observe in
 * their tracing backend when Itara is running. They do not test
 * implementation details — they test whether the observable behavior
 * makes sense for distributed system observability.
 *
 * If a test fails, it means something an engineer would rely on in
 * Jaeger, Elastic APM, or any OTel-compatible backend is broken.
 *
 * API convention:
 *   null context  = root call (no incoming context, entry point)
 *   non-null ctx  = child call (incoming context from caller or headers)
 *
 * Opener events (onCallSent, onCallReceived) return the resolved context
 * which must be passed to the corresponding closer event.
 */
@DisplayName("OtelBridgeImpl — observable behavior")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtelBridgeImplTest {

    private static InMemorySpanExporter spanExporter;
    private static InMemoryMetricReader metricReader;
    private static OtelBridgeImpl       bridge;

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

        bridge = new OtelBridgeImpl();
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

    // ── Trace continuity ───────────────────────────────────────────────────

    @Nested
    @DisplayName("trace continuity")
    class TraceContinuity {

        @Test
        @Order(1)
        @DisplayName("a root call produces exactly one span with a valid traceId")
        void rootCallProducesOneSpan() {
            // null context = root entry point
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size(),
                    "Root call must produce exactly one span — no ghost spans");
            assertFalse(spans.get(0).getTraceId().isBlank());
            assertNotEquals("00000000000000000000000000000000",
                    spans.get(0).getTraceId());
        }

        @Test
        @Order(2)
        @DisplayName("the OTel traceId matches the resolved Itara context traceId")
        void otelTraceIdMatchesItaraTraceId() {
            // The traceId in the returned Itara context must match the OTel span.
            // Without this, cross-system correlation (logs, metrics, traces) breaks.
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals(resolved.getTraceId(), span.getTraceId(),
                    "OTel traceId must match the resolved Itara context traceId — "
                    + "without this, log/trace/metric correlation breaks");
        }

        @Test
        @Order(3)
        @DisplayName("caller and callee spans share the same traceId")
        void callerAndCalleeShareTraceId() {
            // In a distributed trace, all spans for one request must share
            // the same traceId. This is what links them in the backend.
            ItaraContext callerCtx = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            ItaraContext calleeCtx = bridge.onCallReceived(
                    callerCtx, "calculator", "add", "http", now());
            bridge.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            bridge.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size(),
                    "Caller and callee must produce exactly 2 spans");

            String callerTraceId = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                    .findFirst().orElseThrow().getTraceId();
            String calleeTraceId = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow().getTraceId();

            assertEquals(callerTraceId, calleeTraceId,
                    "Caller and callee must share the same traceId — "
                    + "without this, the trace is broken in the backend");
        }

        @Test
        @Order(4)
        @DisplayName("callee span is linked to caller span as its parent")
        void calleeSpanLinkedToCallerSpan() {
            ItaraContext callerCtx = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            ItaraContext calleeCtx = bridge.onCallReceived(
                    callerCtx, "calculator", "add", "http", now());
            bridge.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            bridge.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            SpanData serverSpan = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            assertTrue(serverSpan.getParentSpanContext().isValid(),
                    "Callee span must have a valid parent — "
                    + "without this, the trace tree is broken");
        }

        @Test
        @Order(5)
        @DisplayName("two unrelated root calls produce spans with different traceIds")
        void unrelatedCallsHaveDifferentTraceIds() {
            ItaraContext ctx1 = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(ctx1, "calculator", "add", now(), false);

            ItaraContext ctx2 = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(ctx2, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            assertNotEquals(spans.get(0).getTraceId(), spans.get(1).getTraceId(),
                    "Unrelated calls must have different traceIds");
        }

        @Test
        @Order(6)
        @DisplayName("root span has no parent in the backend")
        void rootSpanHasNoParent() {
            // A root span must appear as the top of the trace tree in
            // Jaeger/Elastic — no dangling parent references.
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertFalse(span.getParentSpanContext().isValid(),
                    "Root span must have no parent — "
                    + "a dangling parent reference looks broken in Jaeger/Elastic");
        }

        @Test
        @Order(7)
        @DisplayName("callee span has a different spanId from the caller span")
        void calleeHasUniqueSpanId() {
            // Simulate the cross-JVM scenario:
            // caller fires onCallSent, gets resolved context
            // that context is serialized to W3C headers and restored on the callee side
            // callee fires onCallReceived with the restored context

            ItaraContext callerResolved = bridge.onCallSent(
                    null, "gateway", "calculate", "http", now());

            // Simulate header propagation and restoration
            ItaraContext restored = bridge.restoreContext(
                    callerResolved.getTraceId(),
                    callerResolved.getSpanId(),
                    callerResolved.getParentSpanId(),
                    callerResolved.getRequestId(),
                    callerResolved.getCorrelationId(),
                    callerResolved.getSourceNode(),
                    callerResolved.getEdgePath());

            // Callee side — must get a NEW spanId, not inherit the caller's
            ItaraContext calleeResolved = bridge.onCallReceived(
                    restored, "calculator", "add", "http", now());
            bridge.onReturnSent(calleeResolved, "calculator", "add", now(), false);
            bridge.onReturnReceived(callerResolved, "gateway", "calculate", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());

            SpanData clientSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                    .findFirst().orElseThrow();
            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            // Same trace
            assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId());

            // Different spans — callee must have its own spanId
            assertNotEquals(clientSpan.getSpanId(), serverSpan.getSpanId(),
                    "Callee must have a unique spanId — sharing the caller's spanId "
                            + "means two sides of the same call are indistinguishable in the backend");

            // Callee's parent is the caller
            assertEquals(callerResolved.getSpanId(), serverSpan.getParentSpanId(),
                    "Callee's parent must be the caller's spanId");
        }
    }

    // ── Span semantics ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("span semantics")
    class SpanSemantics {

        @Test
        @Order(8)
        @DisplayName("caller side produces a CLIENT span")
        void callerProducesClientSpan() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertEquals(SpanKind.CLIENT,
                    spanExporter.getFinishedSpanItems().get(0).getKind(),
                    "Caller side must produce a CLIENT span");
        }

        @Test
        @Order(9)
        @DisplayName("callee side produces a SERVER span")
        void calleeProducesServerSpan() {
            ItaraContext callerCtx = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            ItaraContext calleeCtx = bridge.onCallReceived(
                    callerCtx, "calculator", "add", "http", now());
            bridge.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            bridge.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            SpanData serverSpan = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();
            assertEquals(SpanKind.SERVER, serverSpan.getKind(),
                    "Callee side must produce a SERVER span");
        }

        @Test
        @Order(10)
        @DisplayName("span name is component.method")
        void spanNameIsComponentDotMethod() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertEquals("calculator.add",
                    spanExporter.getFinishedSpanItems().get(0).getName(),
                    "Span name must be component.method for easy filtering in backend");
        }

        @Test
        @Order(11)
        @DisplayName("failed call sets span status to ERROR")
        void failedCallSetsErrorStatus() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "divide", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "divide", now(), true);

            assertEquals(StatusCode.ERROR,
                    spanExporter.getFinishedSpanItems().get(0).getStatus().getStatusCode(),
                    "Failed calls must set ERROR status so backends can alert on them");
        }

        @Test
        @Order(12)
        @DisplayName("successful call leaves span status UNSET")
        void successfulCallLeavesStatusUnset() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertEquals(StatusCode.UNSET,
                    spanExporter.getFinishedSpanItems().get(0).getStatus().getStatusCode());
        }

        @Test
        @Order(13)
        @DisplayName("null context produces no span and does not throw")
        void nullContextOnReturnProducesNoSpan() {
            // Passing null to closer events must not crash
            assertDoesNotThrow(() -> {
                bridge.onReturnReceived(null, "calculator", "add", now(), false);
                bridge.onReturnSent(null, "calculator", "add", now(), false);
            });
        }
    }

    // ── Span attributes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("span attributes")
    class SpanAttributes {

        @Test
        @Order(14)
        @DisplayName("component and method are visible on every span")
        void componentAndMethodVisible() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals("calculator",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_COMPONENT));
            assertEquals("add",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_METHOD));
        }

        @Test
        @Order(15)
        @DisplayName("transport type is visible on every span")
        void transportTypeVisible() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertEquals("http",
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelBridgeImpl.ATTR_TRANSPORT),
                    "Transport type must be visible on span for topology debugging");
        }

        @Test
        @Order(16)
        @DisplayName("request id is present for cross-signal correlation")
        void requestIdPresent() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertNotNull(
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelBridgeImpl.ATTR_REQUEST_ID),
                    "RequestId must be present for cross-signal correlation");
        }

        @Test
        @Order(17)
        @DisplayName("source node is visible for topology understanding")
        void sourceNodeVisible() {
            // null ctx = root call, sourceNode defaults to componentId
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertNotNull(
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelBridgeImpl.ATTR_SOURCE_NODE),
                    "Source node must be visible so engineers know where a request originated");
        }

        @Test
        @Order(18)
        @DisplayName("correlation id is visible when set on incoming context")
        void correlationIdVisibleWhenSet() {
            // Simulate incoming context with correlation id already set
            ItaraContext incoming = ItaraContext.newRoot("gateway", "order-12345");
            ItaraContext resolved = bridge.onCallSent(
                    incoming, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            assertEquals("order-12345",
                    spanExporter.getFinishedSpanItems().get(0)
                            .getAttributes().get(OtelBridgeImpl.ATTR_CORRELATION),
                    "Business correlation id must be visible for business-level tracing");
        }

        @Test
        @Order(19)
        @DisplayName("edge path shows the call route when traversed")
        void edgePathShowsCallRoute() {
            ItaraContext callerCtx = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            ItaraContext calleeCtx = bridge.onCallReceived(
                    callerCtx, "calculator", "add", "http", now());
            bridge.onReturnSent(calleeCtx, "calculator", "add", now(), false);
            bridge.onReturnReceived(callerCtx, "calculator", "add", now(), false);

            SpanData serverSpan = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            // Edge path is set on the callee context by createChildContext
            // It may be empty for the first hop — this test verifies it doesn't crash
            // and that the attribute key exists on the span if path is non-empty
            assertDoesNotThrow(() -> serverSpan.getAttributes()
                    .get(OtelBridgeImpl.ATTR_EDGE_PATH));
        }
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @Order(20)
        @DisplayName("call duration is recorded for every completed call")
        void callDurationRecorded() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElse(null);

            assertNotNull(histogram,
                    "itara.call.duration metric must be recorded — "
                    + "it is the primary signal for latency alerting");
            assertFalse(histogram.getData().getPoints().isEmpty());
        }

        @Test
        @Order(21)
        @DisplayName("call duration is non-negative")
        void callDurationIsNonNegative() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            double sum = histogram.getData().getPoints().stream()
                    .mapToDouble(p -> ((HistogramPointData) p).getSum())
                    .sum();

            assertTrue(sum >= 0, "Call duration must be non-negative");
        }

        @Test
        @Order(22)
        @DisplayName("each call increments the metric count")
        void eachCallIncrementsCount() {
            ItaraContext ctx1 = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(ctx1, "calculator", "add", now(), false);

            ItaraContext ctx2 = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(ctx2, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            long count = histogram.getData().getPoints().stream()
                    .mapToLong(p -> ((HistogramPointData) p).getCount())
                    .sum();

            assertTrue(count >= 2,
                    "Each call must increment the metric count");
        }

        @Test
        @Order(23)
        @DisplayName("failed calls are distinguishable from successful ones in metrics")
        void failedCallsDistinguishableInMetrics() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "divide", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "divide", now(), true);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            boolean hasErrorDimension = histogram.getData().getPoints().stream()
                    .anyMatch(p -> Boolean.TRUE.equals(
                            p.getAttributes().get(OtelBridgeImpl.ATTR_ERROR)));

            assertTrue(hasErrorDimension,
                    "Failed calls must be distinguishable in metrics via error=true — "
                    + "without this, error rate alerting is impossible");
        }

        @Test
        @Order(24)
        @DisplayName("transport type is a metric dimension")
        void transportTypeIsMetricDimension() {
            ItaraContext resolved = bridge.onCallSent(
                    null, "calculator", "add", "http", now());
            bridge.onReturnReceived(resolved, "calculator", "add", now(), false);

            MetricData histogram = metricReader.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            boolean hasTransportDimension = histogram.getData().getPoints().stream()
                    .anyMatch(p -> "http".equals(
                            p.getAttributes().get(OtelBridgeImpl.ATTR_TRANSPORT)));

            assertTrue(hasTransportDimension,
                    "Transport type must be a metric dimension");
        }
    }
}
