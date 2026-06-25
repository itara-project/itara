package io.itara.integration;

import io.itara.agent.ItaraProxyHandler;
import io.itara.agent.failuresemantics.NoopFailureSemantics;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.DispatchHandler;
import io.itara.serializer.json.JsonItaraSerializer;
import io.itara.spi.ItaraSerializer;
import io.itara.transport.kafka.KafkaTransport;
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

        ItaraSerializer serializer = new JsonItaraSerializer();
        String bootstrapServers = kafka.getBootstrapServers();

        Map<String, String> props = Map.of(
                "topic",            TOPIC,
                "bootstrapServers", bootstrapServers,
                "consumerGroup",    CONSUMER_GROUP
        );

        // Consumer side — dispatcher captures received calls for assertions
        consumerTransport = new KafkaTransport();
        DispatchHandler capturingDispatcher = (componentId, methodName, payload, headers) -> {
            receivedPayloads.add(new String(payload));
            receivedHeaders.add(Map.copyOf(headers));
            if (latch != null) latch.countDown();
            return new byte[0];
        };
        consumerTransport.startListener(COMPONENT_ID, props, capturingDispatcher);

        // Producer side — proxy through KafkaTransport
        producerTransport = new KafkaTransport();
        proxy = (OrderPlacedContractProxy) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ OrderPlacedContractProxy.class },
                new ItaraProxyHandler(
                        COMPONENT_ID, serializer, producerTransport,
                        props, ExchangePattern.FIRE_AND_FORGET,
                        new NoopFailureSemantics(),
                        null,
                        null
                )
        );

        // Give the consumer a moment to subscribe before the first send
        Thread.sleep(2_000);
    }

    @AfterAll
    static void teardown() {
        if (consumerTransport != null) consumerTransport.stopListener();
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
    void stopListenerStopsConsumerCleanly() throws InterruptedException {
        KafkaTransport transport = new KafkaTransport();
        transport.startListener(COMPONENT_ID,
                Map.of(
                        "topic",            TOPIC + ".stop-test",
                        "bootstrapServers", kafka.getBootstrapServers(),
                        "consumerGroup",    CONSUMER_GROUP + "-stop-test"
                ),
                (id, method, payload, headers) -> new byte[0]);

        Thread.sleep(500); // let it start
        assertDoesNotThrow(transport::stopListener);
    }

    /**
     * Minimal event contract interface used as the proxy target.
     * Mirrors OrderPlacedContract without pulling in the demo module.
     */
    interface OrderPlacedContractProxy {
        void onOrderPlaced(String orderId, String customerId, double amount);
    }
}
