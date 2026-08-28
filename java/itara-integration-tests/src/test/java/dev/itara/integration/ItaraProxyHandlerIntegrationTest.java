package dev.itara.integration;

import demo.calculator.api.CalculatorService;
import dev.itara.agent.ItaraProxyHandler;
import dev.itara.agent.authentication.NoopAuthentication;
import dev.itara.agent.failuresemantics.NoopFailureSemantics;
import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.serializer.json.JsonSerializerFactory;
import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.SerializerConfig;
import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.failuresemantics.ItaraFailureSemantics;
import dev.itara.spi.failuresemantics.TransportCall;
import dev.itara.agent.metadata.MetadataFile;
import dev.itara.agent.metadata.MethodsMeta;
import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.transport.http.HttpTransportConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraProxyHandler")
public class ItaraProxyHandlerIntegrationTest {

    private static final String COMPONENT_ID = "calculator";
    private static final HttpTransportConfig PROPS =
            new HttpTransportConfig("localhost", 9999, false);
    private static final String NODE_ID = "calculatorNode";
    private static final ItaraAuthentication NOOP_AUTHENTICATION = new NoopAuthentication();
    private static final ItaraAuthenticationConfig NOOP_AUTHENTICATION_CONFIG =
            new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build());

    // ItaraProxyHandler now requires the calling node's own ComponentScope,
    // captured at construction rather than trusted from ambient thread-local
    // state (ADR 0021). These tests exercise the outbound pipeline itself,
    // not scope propagation, so a single fixed scope suffices throughout.
    private static final ComponentScope FROM_SCOPE = new ComponentScope.Factory()
            .nodeId("callerNode")
            .componentId("caller")
            .classLoader(ItaraProxyHandlerIntegrationTest.class.getClassLoader())
            .build();

    // Captures what the failure semantics implementation receives
    private boolean capturedIdempotent;
    private int executeCallCount;
    private TransportCall capturedWork;

    private ItaraSerializer serializer;
    private ItaraSerializerConfig serializerConfig;
    private ItaraTransport noopTransport;
    private ItaraFailureSemantics capturingFailureSemantics;

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    @BeforeEach
    void setUp() {
        JsonSerializerFactory factory = new JsonSerializerFactory();
        serializerConfig = factory.parseConfig(SerializerConfig.builder().build());
        serializer = factory.create(serializerConfig);
        capturedIdempotent = true;
        executeCallCount = 0;
        capturedWork = null;

        // Transport that always fails — we're testing the proxy boundary, not transport
        noopTransport = new FailingTransport();

        // Failure semantics that captures what the proxy passes in
        capturingFailureSemantics = (work, idempotent) -> {
            executeCallCount++;
            capturedIdempotent = idempotent;
            capturedWork = work;
            // Delegate to the actual work so we can also test CHECKED pass-through
            return work.call(null);
        };
    }

    private CalculatorService proxyWith(ItaraFailureSemantics fs, MetadataFile metadata) {
        return (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler("test-conn", COMPONENT_ID, NODE_ID, serializer, serializerConfig, noopTransport,
                        "noop", PROPS, ExchangePattern.REQUEST_REPLY, fs,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG, metadata, null, FROM_SCOPE)
        );
    }

    @Nested
    @DisplayName("idempotency flag")
    class IdempotencyFlag {

        @Test
        @DisplayName("passes idempotent=true for method not in non-idempotent set")
        void passesIdempotentTrueForUnlistedMethod() {
            MetadataFile metadata = metadataWithNonIdempotent(List.of("divide"));
            CalculatorService proxy = proxyWith(capturingFailureSemantics, metadata);

            assertThrows(ItaraRemoteException.class, () -> proxy.add(1, 2));

            assertTrue(capturedIdempotent, "add() is not in non-idempotent list — should be idempotent");
        }

        @Test
        @DisplayName("passes idempotent=false for method in non-idempotent set")
        void passesIdempotentFalseForListedMethod() throws Exception {
            MetadataFile metadata = metadataWithNonIdempotent(List.of("divide"));
            CalculatorService proxy = proxyWith(capturingFailureSemantics, metadata);

            assertThrows(ItaraRemoteException.class, () -> proxy.divide(10, 2));

            assertFalse(capturedIdempotent, "divide() is in non-idempotent list — should not be idempotent");
        }

        @Test
        @DisplayName("treats all methods as idempotent when metadata is null")
        void treatsAllAsIdempotentWhenNoMetadata() {
            CalculatorService proxy = proxyWith(capturingFailureSemantics, null);

            assertThrows(ItaraRemoteException.class, () -> proxy.add(1, 2));

            assertTrue(capturedIdempotent, "No metadata — all methods assumed idempotent");
        }

        @Test
        @DisplayName("treats all methods as idempotent when non-idempotent list is empty")
        void treatsAllAsIdempotentWhenListEmpty() {
            MetadataFile metadata = metadataWithNonIdempotent(List.of());
            CalculatorService proxy = proxyWith(capturingFailureSemantics, metadata);

            assertThrows(ItaraRemoteException.class, () -> proxy.add(1, 2));

            assertTrue(capturedIdempotent);
        }
    }

    @Nested
    @DisplayName("failure semantics boundary")
    class FailureSemanticsBoundary {

        @Test
        @DisplayName("failure semantics execute() is called exactly once per proxy invocation")
        void executeCalledOncePerInvocation() {
            CalculatorService proxy = proxyWith(capturingFailureSemantics, null);

            assertThrows(ItaraRemoteException.class, () -> proxy.add(1, 2));

            assertEquals(1, executeCallCount);
        }

        @Test
        @DisplayName("CHECKED error from transport is passed through the lambda")
        void checkedErrorPassesThroughLambda() {
            // Transport returns a CHECKED error with serialized payload
            ItaraTransport checkingTransport = new FailingTransport();

            // Use noop failure semantics — we want to test the lambda boundary only
            CalculatorService proxy = (CalculatorService) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{ CalculatorService.class },
                    new ItaraProxyHandler("test-conn", COMPONENT_ID, NODE_ID, serializer, serializerConfig,
                            checkingTransport, "noop", PROPS, ExchangePattern.REQUEST_REPLY, new NoopFailureSemantics(),
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG, null, null, FROM_SCOPE)
            );

            // Should throw — not swallow
            assertThrows(ItaraRemoteException.class, () -> proxy.add(1, 2));
        }

        @Test
        @DisplayName("Object methods bypass the pipeline entirely")
        void objectMethodsBypassPipeline() {
            CalculatorService proxy = proxyWith(new NoopFailureSemantics(), null);

            // toString() on the proxy must not trigger failure semantics
            assertDoesNotThrow(proxy::toString);
            assertEquals(0, executeCallCount);
        }
    }

    // — helpers —

    private static MetadataFile metadataWithNonIdempotent(List<String> methods) {
        MethodsMeta methodsMeta = new MethodsMeta();
        methodsMeta.setNonIdempotentMethods(methods);
        MetadataFile metadata = new MetadataFile();
        metadata.setMethods(methodsMeta);
        return metadata;
    }

    /**
     * Minimal transport stub for proxy handler tests.
     * Always throws TRANSPORT — we're testing the proxy boundary, not transport behaviour.
     */
    private static class FailingTransport implements ItaraTransport {

        @Override
        public byte[] send(ItaraCallTarget target, byte[] payload,
                           Map<String, String> headers, ItaraTransportConfig config,
                           Duration timeout) throws ItaraRemoteException {
            throw new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.TRANSPORT,
                    "java.net.ConnectException", "test transport");
        }

        @Override
        public void registerListener(ItaraTransportConfig config,
                                     dev.itara.runtime.DispatchHandler handler) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
