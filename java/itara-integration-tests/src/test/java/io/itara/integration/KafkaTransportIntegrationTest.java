package io.itara.integration;

import io.itara.agent.ItaraProxyHandler;
import io.itara.agent.authentication.NoopAuthentication;
import io.itara.agent.failuresemantics.NoopFailureSemantics;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.DispatchHandler;
import io.itara.serializer.json.JsonSerializerFactory;
import io.itara.spi.authentication.AuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.identity.ItaraTransportCredential;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.SerializerConfig;
import io.itara.transport.kafka.KafkaFailureAction;
import io.itara.transport.kafka.KafkaTransport;
import io.itara.transport.kafka.KafkaTransportConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Kafka transport layer.
 *
 * Spins up a real Kafka broker via Testcontainers and drives it
 * via a real KafkaTransport. No mocks — pure broker communication.
 *
 * Wired the same way the agent wires at startup:
 *   - ItaraDispatcher owns the inbound pipeline
 *   - ItaraProxyHandler owns the outbound pipeline
 *   - KafkaTransport moves bytes — knows nothing else
 *
 * Covers:
 *   - Message arrives on the expected topic
 *   - Headers propagated correctly (itaraTraceId, component-id, method-name)
 *   - Consumer dispatches to the correct handler
 *   - stopListener() stops the consumer cleanly
 */
@DisplayName("Kafka Transport Integration")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KafkaTransportIntegrationTest {

    private static final String TOPIC          = "itara.integration.test";
    private static final String COMPONENT_ID   = "order-events/order-placed";
    private static final String METHOD_NAME    = "onOrderPlaced";
    private static final String CONSUMER_GROUP = "itara-integration-test";
    private static final String NODE_ID = "order-events-node";
    private static final ItaraAuthentication NOOP_AUTHENTICATION = new NoopAuthentication();
    private static final ItaraAuthenticationConfig NOOP_AUTHENTICATION_CONFIG =
            new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build());
    private static final String DISPATCH_KEY   = "test-conn";

    // ItaraProxyHandler now requires the calling node's own ComponentScope
    // (see ADR 0021) — this test only exercises the transport pipeline, not
    // scope content, so a single fixed identity is enough.
    private static final ComponentScope PRODUCER_SCOPE = new ComponentScope.Factory()
            .nodeId("orderProducerNode")
            .componentId("order-producer")
            .classLoader(Thread.currentThread().getContextClassLoader())
            .build();

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private static KafkaTransport producerTransport;
    private static KafkaTransport consumerTransport;
    private static OrderPlacedContractProxy proxy;
    private static final List<String> receivedPayloads = new CopyOnWriteArrayList<>();
    private static final List<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();
    private static CountDownLatch latch;

    @BeforeAll
    static void setup() throws Exception {
        ObservabilityFacade.initialize();

        JsonSerializerFactory serializerFactory = new JsonSerializerFactory();
        ItaraSerializerConfig serializerConfig = serializerFactory.parseConfig(SerializerConfig.builder().build());
        ItaraSerializer serializer = serializerFactory.create(serializerConfig);

        String bootstrapServers = kafka.getBootstrapServers();

        KafkaTransportConfig consumerConfig = new KafkaTransportConfig(
                bootstrapServers, CONSUMER_GROUP, TOPIC, false, KafkaFailureAction.DROP, null);

        KafkaTransportConfig producerConfig = new KafkaTransportConfig(
                bootstrapServers, null, TOPIC, false, KafkaFailureAction.DROP, null);

        consumerTransport = new KafkaTransport(consumerConfig);
        DispatchHandler capturingDispatcher = new CapturingDispatcher();
        consumerTransport.registerListener(consumerConfig, capturingDispatcher);
        consumerTransport.start();

        producerTransport = new KafkaTransport(producerConfig);
        proxy = (OrderPlacedContractProxy) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ OrderPlacedContractProxy.class },
                new ItaraProxyHandler(
                        DISPATCH_KEY, COMPONENT_ID, NODE_ID, serializer, serializerConfig, producerTransport,
                        "kafka",
                        producerConfig, ExchangePattern.FIRE_AND_FORGET,
                        new NoopFailureSemantics(),
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        null,
                        null,
                        PRODUCER_SCOPE
                )
        );

        // Give the consumer a moment to subscribe before the first send
        Thread.sleep(2_000);
    }

    @AfterAll
    static void teardown() {
        if (consumerTransport != null) consumerTransport.stop();
    }

    @BeforeEach
    void resetCapture() {
        receivedPayloads.clear();
        receivedHeaders.clear();
        latch = new CountDownLatch(1);
    }

    @Test
    @Order(1)
    @DisplayName("message arrives on the expected topic and is dispatched")
    void messageArrivesAndIsDispatched() throws Exception {
        proxy.onOrderPlaced("order-001", "cust-001", 99.99);

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "Consumer did not receive the message within 10 seconds");
        assertEquals(1, receivedPayloads.size());
    }

    @Test
    @Order(2)
    @DisplayName("x-itara-component-id header is present and correct")
    void componentIdHeaderPresent() throws Exception {
        proxy.onOrderPlaced("order-002", "cust-002", 49.99);
        latch.await(10, TimeUnit.SECONDS);

        assertFalse(receivedHeaders.isEmpty());
        assertEquals(COMPONENT_ID,
                receivedHeaders.get(0).get(KafkaTransport.HEADER_COMPONENT_ID));
    }

    @Test
    @Order(3)
    @DisplayName("x-itara-method-name header is present and correct")
    void methodNameHeaderPresent() throws Exception {
        proxy.onOrderPlaced("order-003", "cust-003", 19.99);
        latch.await(10, TimeUnit.SECONDS);

        assertFalse(receivedHeaders.isEmpty());
        assertEquals(METHOD_NAME,
                receivedHeaders.get(0).get(KafkaTransport.HEADER_METHOD_NAME));
    }

    @Test
    @Order(4)
    @DisplayName("itaraTraceId header is present and non-empty")
    void itaraTraceIdHeaderPresent() throws Exception {
        proxy.onOrderPlaced("order-004", "cust-004", 29.99);
        latch.await(10, TimeUnit.SECONDS);

        assertFalse(receivedHeaders.isEmpty());
        String traceId = receivedHeaders.get(0).get("x-itara-trace-id");
        assertNotNull(traceId, "x-itara-trace-id header should be present");
        assertEquals(32, traceId.length());
    }

    @Test
    @Order(5)
    @DisplayName("stopListener() stops the consumer cleanly")
    void stopStopsConsumerCleanly() throws Exception {
        KafkaTransportConfig stopTestConfig = new KafkaTransportConfig(
                kafka.getBootstrapServers(),
                CONSUMER_GROUP + "-stop-test",
                TOPIC + ".stop-test",
                false, KafkaFailureAction.DROP, null);

        KafkaTransport transport = new KafkaTransport(stopTestConfig);
        transport.registerListener(stopTestConfig, new NoOpDispatcher());
        transport.start();

        Thread.sleep(500); // let it start
        assertDoesNotThrow(transport::stop);
    }

    /**
     * Minimal event contract interface used as the proxy target.
     * Mirrors OrderPlacedContract without pulling in the demo module.
     */
    interface OrderPlacedContractProxy {
        void onOrderPlaced(String orderId, String customerId, double amount);
    }

    private static class NoOpDispatcher implements DispatchHandler {

        @Override
        public String getDispatchKey() {
            return DISPATCH_KEY;
        }

        @Override
        public byte[] dispatch(byte[] requestBytes, Map<String, String> headers, ItaraTransportCredential credential) throws Exception {
            return new byte[0];
        }
    }

    private static class CapturingDispatcher implements DispatchHandler {

        @Override
        public String getDispatchKey() {
            return DISPATCH_KEY;
        }

        @Override
        public byte[] dispatch(byte[] requestBytes, Map<String, String> headers, ItaraTransportCredential credential) throws Exception {
            receivedPayloads.add(new String(requestBytes));
            receivedHeaders.add(Map.copyOf(headers));
            if (latch != null) latch.countDown();
            return new byte[0];
        }
    }
}
