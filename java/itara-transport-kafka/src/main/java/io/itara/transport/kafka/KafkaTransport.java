package io.itara.transport.kafka;

import io.itara.runtime.DispatchHandler;
import io.itara.spi.ItaraTransport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kafka transport implementation.
 *
 * Moves bytes. Nothing else.
 *
 * Producer side (send): publishes a message to the configured topic, blocks
 * until the broker acknowledges, then returns empty bytes. RETURN_RECEIVED
 * fires on ack — not on consumer completion. This is correct per spec §13.3.1.
 *
 * Consumer side (startListener): starts a background poll loop. Per message,
 * extracts Itara headers and raw payload bytes, calls the DispatchHandler.
 * The response bytes from the dispatcher are discarded — fire-and-forget
 * semantics; there is no caller waiting for a response.
 *
 * Method routing: because Kafka has no URL, componentId and methodName are
 * carried in dedicated message headers alongside the Itara context headers:
 *   x-itara-component-id  — the target component id
 *   x-itara-method-name   — the target method name
 *
 * Properties read:
 *   topic            — Kafka topic name (from virtual node address)
 *   bootstrapServers — comma-separated broker list (from connection entry)
 *   consumerGroup    — consumer group id (from connection entry, consumer side only)
 *
 * Discovered by the agent via META-INF/itara/transport.
 */
public class KafkaTransport implements ItaraTransport {

    public static final String TYPE = "kafka";

    public static final String HEADER_COMPONENT_ID = "x-itara-component-id";
    public static final String HEADER_METHOD_NAME  = "x-itara-method-name";

    private static final Logger log = Logger.getLogger(KafkaTransport.class.getName());

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private volatile KafkaProducer<byte[], byte[]> producer;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private volatile Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public String type() {
        return TYPE;
    }

    // ── Producer side ─────────────────────────────────────────────────────

    /**
     * Publishes the payload to the configured topic and blocks until the
     * broker acknowledges. Returns empty bytes — the caller's RETURN_RECEIVED
     * fires on ack, not on consumer completion (spec §13.3.1).
     *
     * componentId and methodName are carried in message headers so the
     * consumer-side dispatcher knows which method to invoke.
     */
    @Override
    public byte[] send(String componentId,
                       String methodName,
                       byte[] payload,
                       Map<String, String> headers,
                       Map<String, String> properties,
                       Duration timeout) throws Exception {

        String topic            = required(properties, "topic",            componentId);
        String bootstrapServers = required(properties, "bootstrapServers", componentId);

        ensureProducer(bootstrapServers);

        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, payload);

        // Routing headers — consumed by the listener to identify the target method
        record.headers().add(HEADER_COMPONENT_ID,
                componentId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_METHOD_NAME,
                methodName.getBytes(StandardCharsets.UTF_8));

        // Itara context headers — consumed by the dispatcher for context propagation
        headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));

        log.info("[Itara/Kafka] -> " + methodName + " on " + componentId + " to topic " + topic);

        // Block until broker ack — RETURN_RECEIVED fires after this returns
        producer.send(record).get();

        return new byte[0];
    }

    // ── Consumer side ─────────────────────────────────────────────────────

    /**
     * Starts a background poll loop for the configured topic and consumer group.
     * Returns immediately once the consumer is subscribed and the thread is running.
     *
     * Per message: extracts routing and context headers, calls the dispatcher.
     * Response bytes are discarded — fire-and-forget; no caller is waiting.
     */
    @Override
    public void startListener(String componentId,
                              Map<String, String> properties,
                              DispatchHandler dispatcher) {

        String topic            = required(properties, "topic",            componentId);
        String bootstrapServers = required(properties, "bootstrapServers", componentId);
        String consumerGroup    = required(properties, "consumerGroup",    componentId);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG,           consumerGroup);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(
                    KafkaTransport.class.getClassLoader());
            consumer = new KafkaConsumer<>(consumerProps);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        //consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(List.of(topic));
        running.set(true);

        consumerThread = new Thread(() -> pollLoop(dispatcher, componentId),
                "itara-kafka-consumer-" + componentId);
        consumerThread.setDaemon(true);
        consumerThread.start();

        log.info("[Itara/Kafka] Consumer listening on topic " + topic
                + " group " + consumerGroup + " for " + componentId);
    }

    private void pollLoop(DispatchHandler dispatcher, String componentId) {
        while (running.get()) {
            try {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL_TIMEOUT)) {
                    handleRecord(record, dispatcher);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.log(Level.WARNING,
                            "[Itara/Kafka] Poll error for " + componentId
                                    + " — consumer will retry", e);
                }
            }
        }
    }

    private void handleRecord(ConsumerRecord<byte[], byte[]> record,
                              DispatchHandler dispatcher) {
        Headers kafkaHeaders = record.headers();

        String targetComponentId = headerValue(kafkaHeaders, HEADER_COMPONENT_ID);
        String methodName        = headerValue(kafkaHeaders, HEADER_METHOD_NAME);

        if (targetComponentId == null || methodName == null) {
            log.warning("[Itara/Kafka] Skipping message — missing routing headers"
                    + " (x-itara-component-id or x-itara-method-name)");
            return;
        }

        // Collect all headers into the map the dispatcher expects
        Map<String, String> headers = new HashMap<>();
        kafkaHeaders.forEach(h ->
                headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));

        log.info("[Itara/Kafka] <- " + methodName + " on " + targetComponentId);

        try {
            // Response bytes discarded — fire-and-forget, no caller waiting
            dispatcher.dispatch(targetComponentId, methodName, record.value(), headers);
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "[Itara/Kafka] Dispatch error for " + methodName
                            + " on " + targetComponentId + " — message will not be retried", e);
        }
    }

    @Override
    public void stopListener() {
        running.set(false);
        if (consumer != null) {
            consumer.wakeup();  // interrupts poll() cleanly
        }
        if (consumerThread != null) {
            try {
                consumerThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (consumer != null) {
            consumer.close();
            consumer = null;
        }
        if (producer != null) {
            producer.close();
            producer = null;
        }
        log.info("[Itara/Kafka] Transport stopped.");
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private synchronized void ensureProducer(String bootstrapServers) {
        if (producer != null) return;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        // Wait for all in-sync replicas to ack before returning from send()
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(
                    KafkaTransport.class.getClassLoader());
            producer = new KafkaProducer<>(props);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        //producer = new KafkaProducer<>(props);
    }

    private static String headerValue(Headers headers, String key) {
        var header = headers.lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    private static String required(Map<String, String> props, String key, String componentId) {
        String value = props.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[Itara/Kafka] Missing required property '" + key
                            + "' for component '" + componentId + "'");
        }
        return value;
    }
}
