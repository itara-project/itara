package io.itara.integration;

import demo.calculator.api.CalculatorService;
import demo.calculator.component.CalculatorActivator;
import io.itara.agent.ItaraDispatcher;
import io.itara.agent.ItaraProxyHandler;
import io.itara.agent.failuresemantics.NoopFailureSemantics;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.TransportRegistry;
import io.itara.serializer.json.JsonItaraSerializer;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.transport.http.HttpTransport;
import io.itara.transport.http.HttpTransportConfig;
import io.itara.transport.http.HttpTransportFactory;
import io.itara.transport.http.ItaraHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Grouping integration tests for the HTTP transport.
 *
 * Verifies that:
 *   - Two connections on the same port share one HttpTransport instance
 *   - Two connections on different ports get separate instances
 *   - Multiple dispatchers on one server route correctly by component id
 *
 * No Docker required — pure localhost socket communication.
 */
@DisplayName("HTTP Transport Grouping")
public class HttpTransportGroupingIntegrationTest {

    private static final String COMPONENT_A = "calculator";
    private static final String COMPONENT_B = "calculator-b";

    private static int portA;
    private static int portB;
    private static ItaraHttpServer sharedServer;
    private static ItaraHttpServer separateServer;

    @BeforeAll
    static void setUp() throws Exception {
        TransportRegistry.instance().reset();
        TransportRegistry.instance().registerFactory(new HttpTransportFactory());

        ObservabilityFacade.initialize();

        portA = findFreePort();
        portB = findFreePort();

        ItaraRegistry registry = ItaraRegistry.instance();
        registry.registerActivator(COMPONENT_A,
                CalculatorActivator.class, CalculatorService.class);
        registry.registerActivator(COMPONENT_B,
                CalculatorActivator.class, CalculatorService.class);
    }

    @AfterAll
    static void tearDown() {
        if (sharedServer  != null) sharedServer.stop();
        if (separateServer != null) separateServer.stop();
        TransportRegistry.instance().reset();
    }

    // ── Grouping key tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("instance grouping")
    class InstanceGrouping {

        @Test
        @DisplayName("same port returns the same HttpTransport instance")
        void samePortReturnsSameInstance() throws Exception {
            HttpTransportConfig configA = new HttpTransportConfig("localhost", portA, false);
            HttpTransportConfig configB = new HttpTransportConfig("localhost", portA, false);

            // Same port — grouping key is port only, host is irrelevant
            assertEquals(configA.groupingKey(), configB.groupingKey());

            ItaraTransportConfig parsedA = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portA));
            ItaraTransportConfig parsedB = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portA));

            assertSame(
                    TransportRegistry.instance().getOrCreate("http", parsedA),
                    TransportRegistry.instance().getOrCreate("http", parsedB),
                    "Two connections on the same port must share one HttpTransport instance"
            );
        }

        @Test
        @DisplayName("different ports return different HttpTransport instances")
        void differentPortsReturnDifferentInstances() throws Exception {
            ItaraTransportConfig parsedA = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portA));
            ItaraTransportConfig parsedB = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portB));

            assertNotSame(
                    TransportRegistry.instance().getOrCreate("http", parsedA),
                    TransportRegistry.instance().getOrCreate("http", parsedB),
                    "Two connections on different ports must get separate HttpTransport instances"
            );
        }

        @Test
        @DisplayName("grouping key equality is port-only — host does not affect grouping")
        void groupingKeyIsPortOnly() {
            HttpTransportConfig withHost    = new HttpTransportConfig("host-a", portA, false);
            HttpTransportConfig withoutHost = new HttpTransportConfig(null,     portA, false);

            assertEquals(withHost.groupingKey(), withoutHost.groupingKey(),
                    "Host must not affect the grouping key");
        }
    }

    // ── Multi-dispatcher routing ──────────────────────────────────────────

    @Nested
    @DisplayName("multi-dispatcher routing on shared port")
    class MultiDispatcherRouting {

        @Test
        @DisplayName("two components on the same port are both reachable and route correctly")
        void twoComponentsOnSamePortBothReachable() throws Exception {
            ItaraSerializer serializer = new JsonItaraSerializer();

            // Both dispatchers share one transport instance
            ItaraTransportConfig config = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portA));
            HttpTransport transport = (HttpTransport) TransportRegistry.instance()
                    .getOrCreate("http", config);

            ItaraDispatcher dispatcherA = new ItaraDispatcher(
                    COMPONENT_A, "http", serializer,
                    ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY);
            ItaraDispatcher dispatcherB = new ItaraDispatcher(
                    COMPONENT_B, "http", serializer,
                    ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY);

            transport.registerListener(COMPONENT_A, config, dispatcherA);
            transport.registerListener(COMPONENT_B, config, dispatcherB);
            transport.start();

            // Proxy to component A
            CalculatorService proxyA = proxyFor(COMPONENT_A, portA, transport, "http", serializer);
            // Proxy to component B
            CalculatorService proxyB = proxyFor(COMPONENT_B, portA, transport, "http", serializer);

            // Both route correctly through the same server
            assertEquals(7,  proxyA.add(3, 4));
            assertEquals(10, proxyB.add(6, 4));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static io.itara.spi.transport.TransportConfig rawConfig(int port) {
        return io.itara.spi.transport.TransportConfig.builder()
                .params(Map.of("host", "localhost", "port", String.valueOf(port)))
                .build();
    }

    private static CalculatorService proxyFor(String componentId, int port,
                                              HttpTransport transport, String transportId,
                                              ItaraSerializer serializer) {
        return (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler(
                        componentId, serializer, transport, transportId,
                        new HttpTransportConfig("localhost", port, false),
                        ExchangePattern.REQUEST_REPLY,
                        new NoopFailureSemantics(),
                        null, null
                )
        );
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
