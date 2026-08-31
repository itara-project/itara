package dev.itara.transport.kafka;

import dev.itara.runtime.DispatchHandler;
import dev.itara.runtime.DispatchKeyPropagation;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.transport.ItaraTransportConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * One instance per bootstrapServers + consumerGroup combination. Multiple
 * components and topics may be registered on the same instance — they share
 * one KafkaConsumer subscribing to all their topics, and one KafkaProducer
 * to the same cluster.
 */
public class KafkaTransport implements ItaraTransport {

    public static final String HEADER_COMPONENT_ID   = "x-itara-component-id";
    public static final String HEADER_METHOD_NAME    = "x-itara-method-name";
    public static final String HEADER_FAILURE_REASON = "x-itara-failure-reason";

    private static final Logger log = Logger.getLogger(KafkaTransport.class.getName());

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    // Accumulated during registerListener(), consumed by start()
    private final Map<String, List<ListenerConfig>> listeners = new ConcurrentHashMap<>();
    private final Set<String> topics = new HashSet<>();

    private final String bootstrapServers;
    private final String consumerGroup;
    private final KafkaFailureAction failureAction;

    private volatile KafkaProducer<byte[], byte[]> producer;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private volatile Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KafkaTransport(KafkaTransportConfig config) {
        this.bootstrapServers = config.getBootstrapServers();
        this.consumerGroup = config.getConsumerGroup();
        this.failureAction    = config.getFailureAction();
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
    public byte[] send(ItaraCallTarget target,
                       byte[] payload,
                       Map<String, String> headers,
                       ItaraTransportConfig config,
                       Duration timeout) throws Exception {

        KafkaTransportConfig kafkaConfig = (KafkaTransportConfig) config;
        String topic            = kafkaConfig.getTopic();
        String bootstrapServers = kafkaConfig.getBootstrapServers();
        String componentId = target.getComponent();
        String methodName  = target.getMethod();

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

    @Override
    public void registerListener(ItaraTransportConfig config,
                                 DispatchHandler dispatcher) {
        KafkaTransportConfig kafkaConfig = (KafkaTransportConfig) config;
        listeners.computeIfAbsent(dispatcher.getDispatchKey(), ignored -> new ArrayList<>())
                .add(new ListenerConfig(
                        dispatcher,
                        kafkaConfig.getFailureAction(),
                        kafkaConfig.getDlaTopic()));
        topics.add(kafkaConfig.getTopic());
        log.fine("[Itara/Kafka] registered listener for dispatch key='" + dispatcher.getDispatchKey()
                + "' on topic='" + kafkaConfig.getTopic() + "'");
    }

    @Override
    public void start() throws Exception {
        if (listeners.isEmpty()) {
            // Producer-only instance — no consumer needed
            log.fine("[Itara/Kafka] no listeners registered, skipping consumer startup");
            return;
        }

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG,           consumerGroup);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        if (failureAction == KafkaFailureAction.REDELIVER) {
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        } else {
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        }
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

        consumer.subscribe(new ArrayList<>(topics));
        running.set(true);

        consumerThread = new Thread(this::pollLoop,
                "itara-kafka-consumer-" + consumerGroup);
        consumerThread.setDaemon(true);
        consumerThread.start();

        log.info("[Itara/Kafka] Consumer started on topics " + topics
                + " group " + consumerGroup
                + " cluster " + bootstrapServers);
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL_TIMEOUT)) {
                    handleRecord(record);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.log(Level.WARNING,
                            "[Itara/Kafka] Poll error on group " + consumerGroup
                                    + " — consumer will retry", e);
                }
            }
        }
    }

    private void handleRecord(ConsumerRecord<byte[], byte[]> record) {
        Headers kafkaHeaders = record.headers();

        String targetComponentId = headerValue(kafkaHeaders, HEADER_COMPONENT_ID);
        String methodName        = headerValue(kafkaHeaders, HEADER_METHOD_NAME);

        if (targetComponentId == null || methodName == null) {
            log.warning("[Itara/Kafka] Skipping message — missing routing headers"
                    + " (x-itara-component-id or x-itara-method-name)");
            return;
        }

        Map<String, String> headers = new HashMap<>();
        kafkaHeaders.forEach(h ->
                headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));

        String dispatchKey;
        try {
            dispatchKey = DispatchKeyPropagation.decode(headers);
        } catch (IllegalArgumentException e) {
            log.warning("[Itara/Kafka] Skipping message — " + e.getMessage());
            return;
        }

        List<ListenerConfig> matchingListeners = listeners.get(dispatchKey);
        if (matchingListeners == null || matchingListeners.isEmpty()) {
            log.warning("[Itara/Kafka] Skipping message — no listener registered"
                    + " for dispatch key '" + dispatchKey + "'");
            return;
        }

        log.info("[Itara/Kafka] <- " + methodName + " on " + targetComponentId
                + " (dispatchKey=" + dispatchKey + ")");

        RuntimeException redeliveryFailure = null;
        for (ListenerConfig listener : matchingListeners) {
            try {
                listener.dispatcher.dispatch(record.value(), headers, null);
            } catch (Exception e) {
                try {
                    handleDispatchFailure(targetComponentId, methodName, record, headers, listener, e);
                } catch (RuntimeException redeliveryException) {
                    if (redeliveryFailure == null) {
                        redeliveryFailure = redeliveryException;
                    } else {
                        redeliveryFailure.addSuppressed(redeliveryException);
                    }
                }
            }
        }

        if (redeliveryFailure != null) {
            throw redeliveryFailure;
        }
    }

    private void handleDispatchFailure(String componentId,
                                       String methodName,
                                       ConsumerRecord<byte[], byte[]> record,
                                       Map<String, String> headers,
                                       ListenerConfig listener,
                                       Exception cause) {
        switch (listener.failureAction) {
            case DROP:
                log.log(Level.WARNING,
                        "[Itara/Kafka] Dispatch failed for " + methodName
                                + " on " + componentId + " — dropping message (failureAction=drop)",
                        cause);
                break;

            case DLA:
                log.log(Level.WARNING,
                        "[Itara/Kafka] Dispatch failed for " + methodName
                                + " on " + componentId + " — publishing to DLA topic '"
                                + listener.dlaTopic + "'", cause);
                try {
                    ensureProducer(bootstrapServers);
                    ProducerRecord<byte[], byte[]> dlaRecord =
                            new ProducerRecord<>(listener.dlaTopic, record.value());
                    // Copy all original headers to the DLA message
                    headers.forEach((k, v) ->
                            dlaRecord.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
                    // Add failure reason header
                    dlaRecord.headers().add(HEADER_FAILURE_REASON,
                            cause.getMessage() != null
                                    ? cause.getMessage().getBytes(StandardCharsets.UTF_8)
                                    : "unknown".getBytes(StandardCharsets.UTF_8));
                    producer.send(dlaRecord).get();
                } catch (Exception dlaEx) {
                    log.log(Level.SEVERE,
                            "[Itara/Kafka] Failed to publish to DLA topic '"
                                    + listener.dlaTopic + "' — message lost", dlaEx);
                }
                break;

            case REDELIVER:
                log.log(Level.WARNING,
                        "[Itara/Kafka] Dispatch failed for " + methodName
                                + " on " + componentId
                                + " — not acknowledging, Kafka will redeliver", cause);
                // Throwing here interrupts the poll loop commit cycle —
                // with auto-commit disabled this would prevent the offset advancing.
                // With auto-commit enabled (current config) redelivery is best-effort.
                throw new RuntimeException(
                        "[Itara/Kafka] Redelivery requested for " + methodName
                                + " on " + componentId, cause);
        }
    }

    @Override
    public void stop() {
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
    }

    private static String headerValue(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    private static final class ListenerConfig {
        final DispatchHandler dispatcher;
        final KafkaFailureAction failureAction;
        final String dlaTopic;

        ListenerConfig(DispatchHandler dispatcher,
                       KafkaFailureAction failureAction,
                       String dlaTopic) {
            this.dispatcher    = dispatcher;
            this.failureAction = failureAction;
            this.dlaTopic      = dlaTopic;
        }
    }
}
