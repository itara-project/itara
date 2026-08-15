package io.itara.integration;

import demo.calculator.api.CalculatorService;
import demo.calculator.component.CalculatorActivator;
import io.itara.agent.ItaraDispatcher;
import io.itara.agent.ItaraProxyHandler;
import io.itara.agent.failuresemantics.NoopFailureSemantics;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.TransportRegistry;
import io.itara.serializer.json.JsonSerializerFactory;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.SerializerConfig;
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

    // ItaraDispatcher and ItaraProxyHandler now each require a ComponentScope
    // (see ADR 0021). Two node identities for the two dispatchers under test,
    // plus one shared caller identity for every proxy built via proxyFor() —
    // none of these tests exercise scope content itself.
    private static final ComponentScope SCOPE_A = new ComponentScope.Factory()
            .nodeId("calculatorNodeA")
            .componentId(COMPONENT_A)
            .classLoader(Thread.currentThread().getContextClassLoader())
            .build();

    private static final ComponentScope SCOPE_B = new ComponentScope.Factory()
            .nodeId("calculatorNodeB")
            .componentId(COMPONENT_B)
            .classLoader(Thread.currentThread().getContextClassLoader())
            .build();

    private static final ComponentScope CALLER_SCOPE = new ComponentScope.Factory()
            .nodeId("callerNode")
            .componentId("caller")
            .classLoader(Thread.currentThread().getContextClassLoader())
            .build();

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
        registry.registerActivator(COMPONENT_A, CalculatorActivator.class);
        registry.registerActivator(COMPONENT_B, CalculatorActivator.class);
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
            JsonSerializerFactory serializerFactory = new JsonSerializerFactory();
            ItaraSerializerConfig serializerConfig = serializerFactory.parseConfig(SerializerConfig.builder().build());
            ItaraSerializer serializer = serializerFactory.create(serializerConfig);

            // Both dispatchers share one transport instance
            ItaraTransportConfig config = TransportRegistry.instance()
                    .parseConfig("http", rawConfig(portA));
            HttpTransport transport = (HttpTransport) TransportRegistry.instance()
                    .getOrCreate("http", config);

            ItaraDispatcher dispatcherA = new ItaraDispatcher(
                    "conn1", COMPONENT_A, "http", serializer, serializerConfig,
                    ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY, SCOPE_A);
            ItaraDispatcher dispatcherB = new ItaraDispatcher(
                    "conn1", COMPONENT_B, "http", serializer, serializerConfig,
                    ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY, SCOPE_B);

            transport.registerListener(config, dispatcherA);
            transport.registerListener(config, dispatcherB);
            transport.start();

            // Proxy to component A
            CalculatorService proxyA = proxyFor(COMPONENT_A, portA, transport, "http", serializer, serializerConfig);
            // Proxy to component B
            CalculatorService proxyB = proxyFor(COMPONENT_B, portA, transport, "http", serializer, serializerConfig);

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
                                              ItaraSerializer serializer, ItaraSerializerConfig serializerConfig) {
        return (CalculatorService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ CalculatorService.class },
                new ItaraProxyHandler(
                        "conn1", componentId, serializer, serializerConfig, transport, transportId,
                        new HttpTransportConfig("localhost", port, false),
                        ExchangePattern.REQUEST_REPLY,
                        new NoopFailureSemantics(),
                        null, null, CALLER_SCOPE
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
