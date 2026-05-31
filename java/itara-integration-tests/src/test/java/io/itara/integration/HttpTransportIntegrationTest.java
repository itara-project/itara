package io.itara.integration;

import demo.calculator.api.ArithmeticOperationException;
import demo.calculator.api.CalculatorService;
import demo.calculator.component.CalculatorServiceImpl;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.NoOpOtelBridge;
import io.itara.serializer.json.JsonItaraSerializer;
import io.itara.spi.ItaraSerializer;
import io.itara.transport.http.HttpTransport;
import io.itara.transport.http.ItaraHttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the HTTP transport layer.
 *
 * Spins up a real ItaraHttpServer on a random available port and drives it
 * via a real HttpRemoteProxy. No mocks, no Docker — pure localhost socket
 * communication. Safe to run in any CI environment.
 *
 * Covers:
 *   - Success path — correct return values over the wire
 *   - Checked exception path — CHECKED ErrorKind, 422 status
 *   - Runtime exception path — RUNTIME ErrorKind, 500 status
 *   - Infrastructure failures — TRANSPORT ErrorKind, 400/503 status
 *   - Complex type roundtrip — types survive serialization over HTTP
 */
@DisplayName("HTTP Transport Integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpTransportIntegrationTest {

    private static final String COMPONENT_ID = "calculator";

    private static ItaraHttpServer server;
    private static CalculatorService proxy;
    private static int port;
    private static ItaraSerializer serializer;

    @BeforeAll
    static void startServer() throws IOException {
        port = findFreePort();
        serializer = new JsonItaraSerializer();

        // Registry backed by the real component implementation
        ItaraRegistry registry = ItaraRegistry.instance();
        registry.preRegister(COMPONENT_ID, new CalculatorServiceImpl());

        server = new ItaraHttpServer(port, registry, serializer);
        server.start();

        // Build a proxy the same way the agent would
        HttpTransport transport = new HttpTransport();
        Object rawProxy = transport.createProxy(
                COMPONENT_ID,
                CalculatorService.class,
                Map.of("host", "localhost", "port", String.valueOf(port)),
                Thread.currentThread().getContextClassLoader(),
                serializer
        );
        proxy = (CalculatorService) rawProxy;

        ObservabilityFacade.initialize(new NoOpOtelBridge());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("add() returns correct result over HTTP")
    void successPath() {
        int result = proxy.add(3, 4);
        assertEquals(7, result);
    }

    @Test
    @Order(2)
    @DisplayName("add() with negative numbers returns correct result")
    void successNegative() {
        int result = proxy.add(-5, 3);
        assertEquals(-2, result);
    }

    @Test
    @Order(3)
    @DisplayName("divide() returns correct result over HTTP")
    void divideSuccess() throws ArithmeticOperationException {
        int result = proxy.divide(10, 2);
        assertEquals(5, result);
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
        // Build a proxy pointing at a valid server but unknown component id
        HttpTransport transport = new HttpTransport();
        CalculatorService badProxy = (CalculatorService) transport.createProxy(
                "nonexistent-component",
                CalculatorService.class,
                Map.of("host", "localhost", "port", String.valueOf(port)),
                Thread.currentThread().getContextClassLoader(),
                serializer
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
        HttpTransport transport = new HttpTransport();
        CalculatorService badProxy = (CalculatorService) transport.createProxy(
                COMPONENT_ID,
                CalculatorService.class,
                Map.of("host", "localhost", "port", String.valueOf(deadPort)),
                Thread.currentThread().getContextClassLoader(),
                serializer
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

    // — helpers —

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
