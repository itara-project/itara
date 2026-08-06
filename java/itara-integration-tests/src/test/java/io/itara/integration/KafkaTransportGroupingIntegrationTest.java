package io.itara.integration;

import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ItaraCallTarget;
import io.itara.runtime.ObservabilityFacade;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.TransportConfig;
import io.itara.transport.kafka.KafkaFailureAction;
import io.itara.transport.kafka.KafkaTransport;
import io.itara.transport.kafka.KafkaTransportConfig;
import io.itara.transport.kafka.KafkaTransportFactory;
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
            DispatchHandler dispatcherA = (payload, headers, transportCredential) -> {
                receivedByA.add(new String(payload));
                latchA.countDown();
                return new byte[0];
            };
            DispatchHandler dispatcherB = (payload, headers, transportCredential) -> {
                receivedByB.add(new String(payload));
                latchB.countDown();
                return new byte[0];
            };

            sharedTransport.registerListener(COMPONENT_A, parsedA, dispatcherA);
            sharedTransport.registerListener(COMPONENT_B, parsedB, dispatcherB);
            sharedTransport.start();

            // Give consumer time to subscribe
            Thread.sleep(2_000);

            // Producer for topic A → should reach dispatcher A
            KafkaTransportConfig producerConfigA = new KafkaTransportConfig(
                    bootstrapServers, null, TOPIC_A, false, KafkaFailureAction.DROP, null);
            KafkaTransport producerA = new KafkaTransport(producerConfigA);
            producerA.send(ItaraCallTarget.of("grouping-test-node", COMPONENT_A, "onEventA"), "payload-a".getBytes(),
                    Map.of(), parsedA, null);

            // Producer for topic B → should reach dispatcher B
            KafkaTransportConfig producerConfigB = new KafkaTransportConfig(
                    bootstrapServers, null, TOPIC_B, false, KafkaFailureAction.DROP, null);
            KafkaTransport producerB = new KafkaTransport(producerConfigB);
            producerB.send(ItaraCallTarget.of("grouping-test-node", COMPONENT_B, "onEventB"), "payload-b".getBytes(),
                    Map.of(), parsedB, null);

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

    // ── Helpers ───────────────────────────────────────────────────────────

    private TransportConfig rawConfig(String topic, String group) {
        return TransportConfig.builder()
                .params(Map.of("bootstrapServers", bootstrapServers,
                        "consumerGroup",    group))
                .virtualNodeAddress(topic)
                .build();
    }
}
