package io.itara.observability.otel;

import io.itara.runtime.ItaraContext;
import io.itara.runtime.OtelBridge;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.*;

import java.util.Collection;
import java.util.List;

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
    private static OtelBridge observer;

    private static String TRANSPORT = "direct";

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

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        observer = new OtelBridgeImpl();
    }

    @AfterAll
    static void tearDownOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @BeforeEach
    void clearExporters() {
        spanExporter.reset();
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private ItaraContext rootContext(String sourceNode) {
        return ItaraContext.newRoot(sourceNode);
    }

    private ItaraContext childContext(ItaraContext parent, String component) {
        return parent.newChildSpan(component);
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
            ItaraContext ctx = rootContext("gateway");
            long start = now();
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, start);
            long end = now();
            observer.onReturnReceived(resolved, "calculator", "add", end, false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            SpanData span = spans.get(0);
            assertEquals("calculator.add", span.getName());
            assertEquals(SpanKind.CLIENT, span.getKind());
            assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        }

        @Test
        @Order(2)
        @DisplayName("CALL_RECEIVED + RETURN_SENT produces a SERVER span")
        void calleeSpan() {
            ItaraContext ctx = rootContext("gateway").newChildSpan("calculator");
            long start = now();
            ItaraContext resolved = observer.onCallReceived(ctx, "calculator", "add", TRANSPORT, start);
            long end = now();
            observer.onReturnSent(resolved, "calculator", "add", end, false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            SpanData span = spans.get(0);
            assertEquals("calculator.add", span.getName());
            assertEquals(SpanKind.SERVER, span.getKind());
        }

        @Test
        @Order(3)
        @DisplayName("error sets span status to ERROR")
        void errorSpan() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "divide", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "divide", now(), true);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        }

        @Test
        @Order(4)
        @DisplayName("null context is handled gracefully — no span created")
        void nullContext() {
            assertDoesNotThrow(() -> {
                observer.onCallSent(null, "calculator", "add", TRANSPORT, now());
                observer.onReturnReceived(null, "calculator", "add", now(), false);
            });
            assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
        }
    }

    // ── Span attributes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("span attributes")
    class SpanAttributes {

        @Test
        @Order(5)
        @DisplayName("component and method attributes are set")
        void componentAndMethodAttributes() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals("calculator",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_COMPONENT));
            assertEquals("add",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_METHOD));
        }

        @Test
        @Order(6)
        @DisplayName("request id is set as span attribute")
        void requestIdAttribute() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertNotNull(span.getAttributes().get(OtelBridgeImpl.ATTR_REQUEST_ID));
        }

        @Test
        @Order(7)
        @DisplayName("correlation id is set when present")
        void correlationIdAttribute() {
            ItaraContext ctx = ItaraContext.newRoot("gateway", "my-correlation-id");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals("my-correlation-id",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_CORRELATION));
        }

        @Test
        @Order(8)
        @DisplayName("source node is set as span attribute")
        void sourceNodeAttribute() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertEquals("gateway",
                    span.getAttributes().get(OtelBridgeImpl.ATTR_SOURCE_NODE));
        }
    }

    // ── Parent-child relationship ───────────────────────────────────────────

    @Nested
    @DisplayName("parent-child span relationship")
    class ParentChild {

        @Test
        @Order(9)
        @DisplayName("callee span is child of caller span")
        void parentChildRelationship() {
            ItaraContext callerCtx = rootContext("gateway");
           // ItaraContext calleeCtx = callerCtx.newChildSpan("calculator");

            // Caller side
            ItaraContext resolved = observer.onCallSent(callerCtx, "calculator", "add", TRANSPORT, now());
            // Callee side
           // ItaraContext calleeCtx = resolved.newChildSpan("gateway");
            ItaraContext resolved2 = observer.onCallReceived(resolved, "calculator", "add", TRANSPORT, now());
            observer.onReturnSent(resolved2, "calculator", "add", now(), false);
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(2, spans.size());

            // Find caller and callee spans by kind
            SpanData clientSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                    .findFirst().orElseThrow();
            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();

            // Both share the same traceId
            assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId());

            // Server span's parent is the callee context's parentSpanId
            // which is the caller's spanId
            assertEquals(resolved2.getParentSpanId(),
                    serverSpan.getParentSpanId());
        }

        @Test
        @Order(10)
        @DisplayName("root span carries the Itara traceId")
        void rootSpanCarriesItaraTraceId() {
            ItaraContext ctx = rootContext("gateway");

            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);

            assertEquals(ctx.getTraceId(), span.getTraceId());
        }

        @Test
        @Order(11)
        @DisplayName("root span has no parent")
        void rootSpanHasNoParent() {
            ItaraContext ctx = null;//rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            SpanData span = spanExporter.getFinishedSpanItems().get(0);
            assertFalse(span.getParentSpanContext().isValid());
        }
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @Order(12)
        @DisplayName("call duration histogram is recorded")
        void durationHistogramRecorded() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "add", now(), false);

            Collection<MetricData> metrics = metricReader.collectAllMetrics();
            MetricData histogram = metrics.stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(histogram, "itara.call.duration metric not found");
            assertFalse(histogram.getData().getPoints().isEmpty());
        }

        @Test
        @Order(13)
        @DisplayName("histogram count increments per call")
        void histogramCountIncrements() {
            ItaraContext ctx1 = rootContext("gateway");
            ItaraContext resolved1 = observer.onCallSent(ctx1, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved1, "calculator", "add", now(), false);

            ItaraContext ctx2 = rootContext("gateway");
            ItaraContext resolved2 = observer.onCallSent(ctx2, "calculator", "add", TRANSPORT, now());
            observer.onReturnReceived(resolved2, "calculator", "add", now(), false);

            Collection<MetricData> metrics = metricReader.collectAllMetrics();
            MetricData histogram = metrics.stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            long totalCount = histogram.getData().getPoints().stream()
                    .mapToLong(p -> ((HistogramPointData) p).getCount())
                    .sum();

            assertTrue(totalCount >= 2);
        }

        @Test
        @Order(14)
        @DisplayName("error attribute is set on histogram for failed calls")
        void errorAttributeOnHistogram() {
            ItaraContext ctx = rootContext("gateway");
            ItaraContext resolved = observer.onCallSent(ctx, "calculator", "divide", TRANSPORT, now());
            observer.onReturnReceived(resolved, "calculator", "divide", now(), true);

            Collection<MetricData> metrics = metricReader.collectAllMetrics();
            MetricData histogram = metrics.stream()
                    .filter(m -> m.getName().equals("itara.call.duration"))
                    .findFirst().orElseThrow();

            boolean hasErrorPoint = histogram.getData().getPoints().stream()
                    .anyMatch(p -> {
                        Boolean error = p.getAttributes()
                                .get(OtelBridgeImpl.ATTR_ERROR);
                        return Boolean.TRUE.equals(error);
                    });

            assertTrue(hasErrorPoint, "No error=true histogram point found");
        }
    }
}
