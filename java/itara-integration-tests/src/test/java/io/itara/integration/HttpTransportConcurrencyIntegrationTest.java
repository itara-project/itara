package io.itara.integration;

import io.itara.runtime.DispatchHandler;
import io.itara.spi.transport.TransportConfig;
import io.itara.transport.http.HttpTransportConfig;
import io.itara.transport.http.HttpTransportFactory;
import io.itara.transport.http.ItaraHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency integration tests for the HTTP transport server.
 *
 * Verifies that ItaraHttpServer handles concurrent inbound requests in
 * parallel rather than serially on a single thread (issue #122).
 *
 * The dispatcher blocks every request on a shared latch until all requests
 * have arrived. A serial server would never have more than one request in
 * flight, so the latch would never open and the requests would fail —
 * no timing-based assertions needed.
 *
 * No Docker required — pure localhost socket communication.
 */
@DisplayName("HTTP Transport Concurrency")
class HttpTransportConcurrencyIntegrationTest {

    private static final String COMPONENT_ID = "concurrent-component";
    private static final int CONCURRENT_REQUESTS = 4;

    @Test
    @DisplayName("concurrent requests are handled in parallel on distinct threads")
    void concurrentRequestsHandledInParallel() throws Exception {
        int port = findFreePort();

        CountDownLatch allInFlight = new CountDownLatch(CONCURRENT_REQUESTS);
        Set<String> handlerThreads = ConcurrentHashMap.newKeySet();

        DispatchHandler blockingDispatcher = (componentId, methodName, requestBytes, headers) -> {
            handlerThreads.add(Thread.currentThread().getName());
            allInFlight.countDown();
            if (!allInFlight.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Requests were not handled in parallel");
            }
            return "ok".getBytes(StandardCharsets.UTF_8);
        };

        ItaraHttpServer server = new ItaraHttpServer(
                port, Map.of(COMPONENT_ID, blockingDispatcher));
        server.start();
        try {
            List<Integer> statuses = fireConcurrentRequests(port, CONCURRENT_REQUESTS);

            for (int status : statuses) {
                assertEquals(200, status, "Every concurrent request must succeed");
            }
            assertEquals(0, allInFlight.getCount(),
                    "All " + CONCURRENT_REQUESTS + " requests must be in flight simultaneously");
            assertTrue(handlerThreads.size() > 1,
                    "Concurrent requests must be handled on more than one thread, saw: "
                            + handlerThreads);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("explicitly configured pool size still serves that many requests in parallel")
    void configuredPoolSizeHandlesThatManyInParallel() throws Exception {
        int port = findFreePort();
        int poolSize = 2;

        CountDownLatch bothInFlight = new CountDownLatch(poolSize);
        DispatchHandler blockingDispatcher = (componentId, methodName, requestBytes, headers) -> {
            bothInFlight.countDown();
            if (!bothInFlight.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Requests were not handled in parallel");
            }
            return "ok".getBytes(StandardCharsets.UTF_8);
        };

        ItaraHttpServer server = new ItaraHttpServer(
                port, Map.of(COMPONENT_ID, blockingDispatcher), poolSize);
        server.start();
        try {
            List<Integer> statuses = fireConcurrentRequests(port, poolSize);
            for (int status : statuses) {
                assertEquals(200, status);
            }
        } finally {
            server.stop();
        }
    }

    // ── Config parsing ────────────────────────────────────────────────────

    @Nested
    @DisplayName("threadPoolSize config parsing")
    class ThreadPoolSizeParsing {

        private final HttpTransportFactory factory = new HttpTransportFactory();

        private HttpTransportConfig parse(Map<String, String> params) throws Exception {
            return (HttpTransportConfig) factory.parseConfig(
                    TransportConfig.builder().params(params).build());
        }

        @Test
        @DisplayName("defaults when threadPoolSize is absent")
        void defaultsWhenAbsent() throws Exception {
            HttpTransportConfig config = parse(Map.of("port", "8080"));
            assertEquals(HttpTransportConfig.DEFAULT_THREAD_POOL_SIZE,
                    config.getThreadPoolSize());
        }

        @Test
        @DisplayName("parses an explicit threadPoolSize")
        void parsesExplicitValue() throws Exception {
            HttpTransportConfig config = parse(
                    Map.of("port", "8080", "threadPoolSize", "32"));
            assertEquals(32, config.getThreadPoolSize());
        }

        @Test
        @DisplayName("rejects a non-integer threadPoolSize")
        void rejectsNonInteger() {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(Map.of("port", "8080", "threadPoolSize", "many")));
        }

        @Test
        @DisplayName("rejects a non-positive threadPoolSize")
        void rejectsNonPositive() {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(Map.of("port", "8080", "threadPoolSize", "0")));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static List<Integer> fireConcurrentRequests(int port, int count) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> inFlight = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                inFlight.add(clients.submit(() -> post(port)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : inFlight) {
                statuses.add(response.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            clients.shutdownNow();
        }
    }

    private static int post(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://localhost:" + port + "/itara/" + COMPONENT_ID + "/work")
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(30_000);
        try (OutputStream out = connection.getOutputStream()) {
            out.write("payload".getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
