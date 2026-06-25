package io.itara.integration;

import demo.calculator.api.ArithmeticOperationException;
import demo.calculator.api.CalculatorService;
import demo.calculator.component.CalculatorActivator;
import io.itara.agent.ItaraDispatcher;
import io.itara.agent.ItaraProxyHandler;
import io.itara.agent.failuresemantics.NoopFailureSemantics;
import io.itara.exceptions.ItaraReconstructibleException;
import io.itara.exceptions.ItaraReconstructibleExceptionFactory;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.serializer.json.JsonItaraSerializer;
import io.itara.spi.ItaraSerializer;
import io.itara.transport.http.HttpTransport;
import io.itara.transport.http.ItaraHttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the HTTP transport layer.
 *
 * Spins up a real ItaraHttpServer on a random available port and drives it
 * via a real HttpRemoteProxy. No mocks, no Docker — pure localhost socket
 * communication. Safe to run in any CI environment.
 *
 * Wired the same way the agent wires at startup:
 *   - ItaraDispatcher owns the inbound pipeline (observability, serialization, registry)
 *   - ItaraProxyHandler owns the outbound pipeline (observability, serialization, transport)
 *   - HttpTransport moves bytes — knows nothing else
 *
 * Covers:
 *   - Success path — correct return values over the wire
 *   - Checked exception path — CHECKED ErrorKind, 422 status
 *   - Runtime exception path — RUNTIME ErrorKind, 500 status
 *   - Infrastructure failures — TRANSPORT ErrorKind
 *   - Error message preservation across the wire
 */
@DisplayName("HTTP Transport Integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpTransportIntegrationTest {

    private static final String COMPONENT_ID = "calculator";

    private static ItaraHttpServer server;
    private static CalculatorService proxy;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        port = findFreePort();

        ItaraSerializer serializer = new JsonItaraSerializer();
        HttpTransport transport = new HttpTransport();

        ObservabilityFacade.initialize();

        // Registry — pre-register the raw implementation for the dispatcher
        ItaraRegistry registry = ItaraRegistry.instance();
        registry.registerActivator(COMPONENT_ID,
                CalculatorActivator.class,
                CalculatorService.class
        );

        // Inbound — dispatcher owns the pipeline, transport delivers bytes to it
        ItaraDispatcher dispatcher = new ItaraDispatcher(
                COMPONENT_ID, HttpTransport.TYPE, serializer, registry, ExchangePattern.REQUEST_REPLY
        );
        server = new ItaraHttpServer(port, dispatcher);
        server.start();

        // Outbound — proxy handler owns the pipeline, transport is a byte carrier
        proxy = (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler(
                        COMPONENT_ID, serializer, transport,
                        Map.of("host", "localhost", "port", String.valueOf(port)),
                        ExchangePattern.REQUEST_REPLY,
                        new NoopFailureSemantics(),
                        null,
                        null
                )
        );
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @Order(1)
    @DisplayName("add() returns correct result over HTTP")
    void successPath() {
        assertEquals(7, proxy.add(3, 4));
    }

    @Test
    @Order(2)
    @DisplayName("add() with negative numbers returns correct result")
    void successNegative() {
        assertEquals(-2, proxy.add(-5, 3));
    }

    @Test
    @Order(3)
    @DisplayName("divide() returns correct result over HTTP")
    void divideSuccess() throws ArithmeticOperationException {
        assertEquals(5, proxy.divide(10, 2));
    }

    @Test
    @Order(4)
    @DisplayName("divide() by zero throws ItaraRemoteException with CHECKED kind")
    void checkedExceptionPath() {
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> proxy.divide(10, 0)
        );
        assertAll(
                () -> assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind()),
                () -> assertEquals(
                        "demo.calculator.api.ArithmeticOperationException",
                        ex.getRemoteExceptionClass()),
                () -> assertEquals("Division by zero is not allowed", ex.getMessage())
        );
    }

    @Test
    @Order(5)
    @DisplayName("component runtime exception produces RUNTIME kind")
    void runtimeExceptionPath() {
        // Integer.MIN_VALUE / -1 overflows. It's not a problem in Java,
        // but the code was adjusted so it triggers ArithmeticException which is a RuntimeException
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> proxy.divide(Integer.MIN_VALUE, -1)
        );
        assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, ex.getErrorKind());
    }

    @Test
    @Order(6)
    @DisplayName("calling unknown component produces TRANSPORT kind")
    void unknownComponentProducesTransport() throws IOException {
        CalculatorService badProxy = (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler(
                        "nonexistent-component",
                        new JsonItaraSerializer(),
                        new HttpTransport(),
                        Map.of("host", "localhost", "port", String.valueOf(port)),
                        ExchangePattern.REQUEST_REPLY,
                        new NoopFailureSemantics(),
                        null,
                        null
                )
        );
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> badProxy.add(1, 2)
        );
        assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
    }

    @Test
    @Order(7)
    @DisplayName("calling unreachable server produces TRANSPORT kind")
    void unreachableServerProducesTransport() throws IOException {
        int deadPort = findFreePort(); // Nothing listening here
        CalculatorService badProxy = (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler(
                        COMPONENT_ID,
                        new JsonItaraSerializer(),
                        new HttpTransport(),
                        Map.of("host", "localhost", "port", String.valueOf(deadPort)),
                        ExchangePattern.REQUEST_REPLY,
                        new NoopFailureSemantics(),
                        null,
                        null
                )
        );
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> badProxy.add(1, 2)
        );
        assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
    }

    @Test
    @Order(8)
    @DisplayName("error message is preserved across the wire")
    void errorMessagePreserved() {
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> proxy.divide(10, 0)
        );
        assertEquals("Division by zero is not allowed", ex.getMessage());
    }

    @Test
    @Order(9)
    @DisplayName("toString() includes ErrorKind and class name")
    void toStringFormat() {
        ItaraRemoteException ex = assertThrows(
                ItaraRemoteException.class,
                () -> proxy.divide(10, 0)
        );
        String str = ex.toString();
        assertTrue(str.contains("CHECKED"));
        assertTrue(str.contains("ArithmeticOperationException"));
    }

    @Nested
    @DisplayName("checked error reconstruction")
    class CheckedErrorReconstruction {

        private CalculatorService proxyWithFactory() {
            return (CalculatorService) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{ CalculatorService.class },
                    new ItaraProxyHandler(
                            COMPONENT_ID, new JsonItaraSerializer(), new HttpTransport(),
                            Map.of("host", "localhost", "port", String.valueOf(port)),
                            ExchangePattern.REQUEST_REPLY,
                            new NoopFailureSemantics(),
                            null,
                            new CalculatorExceptionFactory()
                    )
            );
        }

        private CalculatorService proxyWithNoFactory() {
            return (CalculatorService) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{ CalculatorService.class },
                    new ItaraProxyHandler(
                            COMPONENT_ID, new JsonItaraSerializer(), new HttpTransport(),
                            Map.of("host", "localhost", "port", String.valueOf(port)),
                            ExchangePattern.REQUEST_REPLY,
                            new NoopFailureSemantics(),
                            null,
                            null
                    )
            );
        }

        @Test
        @DisplayName("CHECKED error is reconstructed as the original exception type when factory is registered")
        void reconstructsCheckedError() {
            assertThrows(
                    ArithmeticOperationException.class,
                    () -> proxyWithFactory().divide(10, 0)
            );
        }

        @Test
        @DisplayName("reconstructed exception preserves the original message")
        void preservesMessage() throws Exception {
            ArithmeticOperationException ex = assertThrows(
                    ArithmeticOperationException.class,
                    () -> proxyWithFactory().divide(10, 0)
            );
            assertEquals("Division by zero is not allowed", ex.getMessage());
        }

        @Test
        @DisplayName("falls back to ItaraRemoteException when no factory is registered")
        void fallsBackWhenNoFactory() {
            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class,
                    () -> proxyWithNoFactory().divide(10, 0)
            );
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind());
        }

        @Test
        @DisplayName("falls back to ItaraRemoteException when factory returns empty")
        void fallsBackWhenFactoryReturnsEmpty() {
            // Factory that always returns empty — simulates opt-out for a specific type
            ItaraReconstructibleExceptionFactory emptyFactory =
                    new ItaraReconstructibleExceptionFactory() {
                        @Override
                        public String contractId() { return COMPONENT_ID; }

                        @Override
                        public Optional<ItaraReconstructibleException> reconstruct(
                                String errorTypeId, String message) {
                            return Optional.empty();
                        }
                    };

            CalculatorService proxy = (CalculatorService) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{ CalculatorService.class },
                    new ItaraProxyHandler(
                            COMPONENT_ID, new JsonItaraSerializer(), new HttpTransport(),
                            Map.of("host", "localhost", "port", String.valueOf(port)),
                            ExchangePattern.REQUEST_REPLY,
                            new NoopFailureSemantics(),
                            null,
                            emptyFactory
                    )
            );

            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class,
                    () -> proxy.divide(10, 0)
            );
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind());
        }

        @Test
        @DisplayName("RUNTIME error is not reconstructed even when factory is registered")
        void runtimeErrorNotReconstructed() {
            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class,
                    () -> proxyWithFactory().divide(Integer.MIN_VALUE, -1)
            );
            assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, ex.getErrorKind());
        }

        @Test
        @DisplayName("falls back to ItaraRemoteException when factory returns a type not declared on the method")
        void fallsBackWhenReconstructedTypeNotDeclaredOnMethod() {
            // Factory returns a type that is not declared on CalculatorService.divide()
            ItaraReconstructibleExceptionFactory wrongTypeFactory =
                    new ItaraReconstructibleExceptionFactory() {

                        // A reconstructible exception that is NOT declared on divide()
                        class UndeclaredReconstructibleException
                                extends Exception implements ItaraReconstructibleException {
                            UndeclaredReconstructibleException(String message) { super(message); }
                        }

                        @Override
                        public String contractId() { return COMPONENT_ID; }

                        @Override
                        public Optional<ItaraReconstructibleException> reconstruct(
                                String errorTypeId, String message) {
                            return Optional.of(new UndeclaredReconstructibleException(message));
                        }
                    };

            CalculatorService proxy = (CalculatorService) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{ CalculatorService.class },
                    new ItaraProxyHandler(
                            COMPONENT_ID, new JsonItaraSerializer(), new HttpTransport(),
                            Map.of("host", "localhost", "port", String.valueOf(port)),
                            ExchangePattern.REQUEST_REPLY,
                            new NoopFailureSemantics(),
                            null,
                            wrongTypeFactory
                    )
            );

            // Must not throw UndeclaredThrowableException — falls back cleanly
            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class,
                    () -> proxy.divide(10, 0)
            );
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind());
        }

        @Test
        @DisplayName("fallback preserves error kind and remote exception class from the payload")
        void fallbackPreservesPayloadDetails() {
            ItaraRemoteException ex = assertThrows(
                    ItaraRemoteException.class,
                    () -> proxyWithNoFactory().divide(10, 0)
            );
            assertAll(
                    () -> assertEquals(ItaraRemoteException.ErrorKind.CHECKED, ex.getErrorKind()),
                    () -> assertEquals("demo.calculator.api.ArithmeticOperationException",
                            ex.getRemoteExceptionClass()),
                    () -> assertEquals("Division by zero is not allowed", ex.getMessage())
            );
        }
    }

    // ── Reconstruction fixtures ───────────────────────────────────────────

    /**
     * Factory that reconstructs ReconstructibleArithmeticException.
     * Returns empty for any other error type id.
     */
    static class CalculatorExceptionFactory
            implements ItaraReconstructibleExceptionFactory {

        @Override
        public String contractId() { return COMPONENT_ID; }

        @Override
        public Optional<ItaraReconstructibleException> reconstruct(
                String errorTypeId, String message) {
            if ("demo.calculator.api.ArithmeticOperationException".equals(errorTypeId)) {
                return Optional.of(new ArithmeticOperationException(message));
            }
            return Optional.empty();
        }
    }

    // — helpers —

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
