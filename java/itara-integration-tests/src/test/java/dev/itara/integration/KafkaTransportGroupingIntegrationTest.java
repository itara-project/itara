package dev.itara.integration;

import dev.itara.runtime.DispatchHandler;
import dev.itara.runtime.DispatchKeyPropagation;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.runtime.TransportRegistry;
import dev.itara.spi.identity.ItaraTransportCredential;
import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.spi.transport.TransportConfig;
import dev.itara.transport.kafka.KafkaFailureAction;
import dev.itara.transport.kafka.KafkaTransport;
import dev.itara.transport.kafka.KafkaTransportConfig;
import dev.itara.transport.kafka.KafkaTransportFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grouping integration tests for the Kafka transport.
 *
 * Verifies that:
 *   - Same bootstrapServers + consumerGroup → shared KafkaTransport instance
 *   - Same cluster, different consumer group → separate instances
 *   - Multiple components on one shared instance route correctly by component id header
 *
 * Requires Docker — disabled automatically when Docker is unavailable.
 */
@DisplayName("Kafka Transport Grouping")
@Testcontainers(disabledWithoutDocker = true)
public class KafkaTransportGroupingIntegrationTest {

    private static final String TOPIC_A        = "itara.grouping.topic-a";
    private static final String TOPIC_B        = "itara.grouping.topic-b";
    private static final String COMPONENT_A    = "service-a";
    private static final String COMPONENT_B    = "service-b";
    private static final String GROUP_SHARED   = "itara-grouping-shared";
    private static final String GROUP_SEPARATE = "itara-grouping-separate";
    private static final String DISPATCH_KEY_A = "conn-001";
    private static final String DISPATCH_KEY_B = "conn-002";

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private static String bootstrapServers;

    private final List<String> receivedByA = new CopyOnWriteArrayList<>();
    private final List<String> receivedByB = new CopyOnWriteArrayList<>();
    private CountDownLatch latchA;
    private CountDownLatch latchB;

    @BeforeAll
    static void setUp() {
        ObservabilityFacade.initialize();
        bootstrapServers = kafka.getBootstrapServers();
    }

    @BeforeEach
    void resetRegistry() {
        TransportRegistry.instance().reset();
        TransportRegistry.instance().registerFactory(new KafkaTransportFactory());
    }

    @AfterAll
    static void tearDown() {
        TransportRegistry.instance().reset();
    }

    @BeforeEach
    void resetCapture() {
        receivedByA.clear();
        receivedByB.clear();
        latchA = new CountDownLatch(1);
        latchB = new CountDownLatch(1);
    }

    // ── Instance grouping ─────────────────────────────────────────────────

    @Nested
    @DisplayName("instance grouping")
    class InstanceGrouping {

        @Test
        @DisplayName("same bootstrapServers and consumerGroup return same instance")
        void sameClusterAndGroupReturnSameInstance() throws Exception {
            ItaraTransportConfig c1 = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_A, GROUP_SHARED));
            ItaraTransportConfig c2 = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_B, GROUP_SHARED));

            ItaraTransport t1 = TransportRegistry.instance().getOrCreate("kafka", c1);
            ItaraTransport t2 = TransportRegistry.instance().getOrCreate("kafka", c2);

            assertSame(t1, t2,
                    "Same cluster + group must share one KafkaTransport instance");
        }

        @Test
        @DisplayName("same cluster but different consumer group returns different instances")
        void differentGroupReturnsDifferentInstance() throws Exception {
            ItaraTransportConfig c1 = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_A, GROUP_SHARED));
            ItaraTransportConfig c2 = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_A, GROUP_SEPARATE));

            ItaraTransport t1 = TransportRegistry.instance().getOrCreate("kafka", c1);
            ItaraTransport t2 = TransportRegistry.instance().getOrCreate("kafka", c2);

            assertNotSame(t1, t2,
                    "Different consumer groups must get separate KafkaTransport instances");
        }

        @Test
        @DisplayName("grouping key equality ignores topic — topic does not affect grouping")
        void groupingKeyIgnoresTopic() {
            KafkaTransportConfig configTopicA =
                    new KafkaTransportConfig(bootstrapServers, GROUP_SHARED, TOPIC_A, false, KafkaFailureAction.DROP, null);
            KafkaTransportConfig configTopicB =
                    new KafkaTransportConfig(bootstrapServers, GROUP_SHARED, TOPIC_B, false, KafkaFailureAction.DROP, null);

            assertEquals(configTopicA.groupingKey(), configTopicB.groupingKey(),
                    "Topic must not affect the grouping key");
        }
    }

    // ── Multi-component routing ───────────────────────────────────────────

    @Nested
    @DisplayName("multi-component routing on shared instance")
    class MultiComponentRouting {

        @Test
        @DisplayName("two components on the same consumer group instance each receive their messages")
        void twoComponentsOnSharedInstanceRouteCorrectly() throws Exception {
            KafkaTransportConfig configA = new KafkaTransportConfig(
                    bootstrapServers, GROUP_SHARED, TOPIC_A, false, KafkaFailureAction.DROP, null);
            KafkaTransportConfig configB = new KafkaTransportConfig(
                    bootstrapServers, GROUP_SHARED, TOPIC_B, false, KafkaFailureAction.DROP, null);

            // Both configs produce the same grouping key — one shared instance
            ItaraTransportConfig parsedA = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_A, GROUP_SHARED));
            ItaraTransportConfig parsedB = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(TOPIC_B, GROUP_SHARED));

            KafkaTransport sharedTransport = (KafkaTransport) TransportRegistry.instance()
                    .getOrCreate("kafka", parsedA);

            assertSame(sharedTransport,
                    TransportRegistry.instance().getOrCreate("kafka", parsedB));

            // Register both dispatchers on the shared instance
            DispatchHandler dispatcherA = new TestDispatcher(receivedByA, latchA, DISPATCH_KEY_A);
            DispatchHandler dispatcherB = new TestDispatcher(receivedByB, latchB, DISPATCH_KEY_B);

            sharedTransport.registerListener(parsedA, dispatcherA);
            sharedTransport.registerListener(parsedB, dispatcherB);
            sharedTransport.start();

            // Give consumer time to subscribe
            Thread.sleep(2_000);

            // Producer for topic A → should reach dispatcher A
            KafkaTransportConfig producerConfigA = new KafkaTransportConfig(
                    bootstrapServers, null, TOPIC_A, false, KafkaFailureAction.DROP, null);
            KafkaTransport producerA = new KafkaTransport(producerConfigA);
            producerA.send(ItaraCallTarget.of("grouping-test-node", COMPONENT_A, "onEventA"), "payload-a".getBytes(),
                    Map.of("x-itara-component-id", COMPONENT_A,
                            "x-itara-method-name",  "onEventA",
                            DispatchKeyPropagation.HEADER_DISPATCH_KEY, DISPATCH_KEY_A),
                    parsedA, null);

            // Producer for topic B → should reach dispatcher B
            KafkaTransportConfig producerConfigB = new KafkaTransportConfig(
                    bootstrapServers, null, TOPIC_B, false, KafkaFailureAction.DROP, null);
            KafkaTransport producerB = new KafkaTransport(producerConfigB);
            producerB.send(ItaraCallTarget.of("grouping-test-node", COMPONENT_B, "onEventB"), "payload-b".getBytes(),
                    Map.of("x-itara-component-id", COMPONENT_B,
                            "x-itara-method-name",  "onEventB",
                            DispatchKeyPropagation.HEADER_DISPATCH_KEY, DISPATCH_KEY_B),
                    parsedB, null);

            assertTrue(latchA.await(10, TimeUnit.SECONDS),
                    "Dispatcher A did not receive message within 10 seconds");
            assertTrue(latchB.await(10, TimeUnit.SECONDS),
                    "Dispatcher B did not receive message within 10 seconds");

            assertEquals(1, receivedByA.size(), "Dispatcher A should have received exactly one message");
            assertEquals(1, receivedByB.size(), "Dispatcher B should have received exactly one message");
            assertEquals("payload-a", receivedByA.get(0));
            assertEquals("payload-b", receivedByB.get(0));

            sharedTransport.stop();
        }
    }

    // ── Dispatch key edge cases ─────────────────────────────────────────────

    @Nested
    @DisplayName("dispatch key edge cases")
    class DispatchKeyEdgeCases {

        @Test
        @DisplayName("a message carrying an unrecognized dispatch key is dropped, not misrouted to a registered dispatcher")
        void unrecognizedDispatchKeyIsDroppedNotMisrouted() throws Exception {
            String topic = "itara.grouping.unrecognized-key";
            String group = "itara-grouping-unrecognized-key";

            ItaraTransportConfig parsed = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(topic, group));
            KafkaTransport transport = (KafkaTransport) TransportRegistry.instance()
                    .getOrCreate("kafka", parsed);

            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            DispatchHandler registered = new TestDispatcher(received, latch, "conn-registered");

            transport.registerListener(parsed, registered);
            transport.start();
            Thread.sleep(2_000);

            KafkaTransportConfig producerConfig = new KafkaTransportConfig(
                    bootstrapServers, null, topic, false, KafkaFailureAction.DROP, null);
            KafkaTransport producer = new KafkaTransport(producerConfig);
            producer.send(ItaraCallTarget.of("whatever", "whatever", "onSomething"), "payload".getBytes(),
                    Map.of("x-itara-component-id", "whatever",
                            "x-itara-method-name",  "onSomething",
                            DispatchKeyPropagation.HEADER_DISPATCH_KEY, "conn-DOES-NOT-EXIST"),
                    producerConfig, null);

            boolean delivered = latch.await(5, TimeUnit.SECONDS);
            assertFalse(delivered, "message with an unrecognized dispatch key must not reach any dispatcher");
            assertTrue(received.isEmpty());

            transport.stop();
        }

        @Test
        @DisplayName("multiple dispatchers registered under the same dispatch key — event-driven fan-out — all receive the message")
        void multipleDispatchersOnSameKeyAllReceiveTheEvent() throws Exception {
            String topic     = "itara.grouping.fanout";
            String group     = "itara-grouping-fanout";
            String sharedKey = "orderPlacedChannel";

            ItaraTransportConfig parsed = TransportRegistry.instance()
                    .parseConfig("kafka", rawConfig(topic, group));
            KafkaTransport transport = (KafkaTransport) TransportRegistry.instance()
                    .getOrCreate("kafka", parsed);

            List<String> receivedBySubscriber1 = new CopyOnWriteArrayList<>();
            List<String> receivedBySubscriber2 = new CopyOnWriteArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            DispatchHandler subscriber1 = new TestDispatcher(receivedBySubscriber1, latch1, sharedKey);
            DispatchHandler subscriber2 = new TestDispatcher(receivedBySubscriber2, latch2, sharedKey);

            transport.registerListener(parsed, subscriber1);
            transport.registerListener(parsed, subscriber2);
            transport.start();
            Thread.sleep(2_000);

            KafkaTransportConfig producerConfig = new KafkaTransportConfig(
                    bootstrapServers, null, topic, false, KafkaFailureAction.DROP, null);
            KafkaTransport producer = new KafkaTransport(producerConfig);
            producer.send(ItaraCallTarget.of("order-events/order-placed", "order-events/order-placed", "onOrderPlaced"), "payload".getBytes(),
                    Map.of("x-itara-component-id", "order-events/order-placed",
                            "x-itara-method-name",  "onOrderPlaced",
                            DispatchKeyPropagation.HEADER_DISPATCH_KEY, sharedKey),
                    producerConfig, null);

            assertTrue(latch1.await(10, TimeUnit.SECONDS), "subscriber 1 must receive the event");
            assertTrue(latch2.await(10, TimeUnit.SECONDS), "subscriber 2 must receive the event");
            assertEquals(1, receivedBySubscriber1.size());
            assertEquals(1, receivedBySubscriber2.size());

            transport.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TransportConfig rawConfig(String topic, String group) {
        return TransportConfig.builder()
                .params(Map.of("bootstrapServers", bootstrapServers,
                        "consumerGroup",    group))
                .virtualNodeAddress(topic)
                .build();
    }

    private static class TestDispatcher implements DispatchHandler {
        private final List<String> receivedBy;
        private CountDownLatch latch;
        private final String key;

        public TestDispatcher(List<String> receivedBy, CountDownLatch latch, String dispatchKey) {
            this.receivedBy = receivedBy;
            this.latch = latch;
            this.key = dispatchKey;
        }

        @Override
        public String getDispatchKey() {
            return key;
        }

        @Override
        public byte[] dispatch(byte[] requestBytes, Map<String, String> headers, ItaraTransportCredential transportCredential) throws Exception {
            receivedBy.add(new String(requestBytes));
            latch.countDown();
            return new byte[0];
        }
    }
}
