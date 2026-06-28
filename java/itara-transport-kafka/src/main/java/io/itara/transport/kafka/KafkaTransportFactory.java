package io.itara.transport.kafka;

import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.ItaraTransportFactory;
import io.itara.spi.transport.TransportConfig;

/**
 * Factory for KafkaTransport instances.
 *
 * Registered via META-INF/itara/transport. One instance is created per
 * unique bootstrapServers + consumerGroup combination.
 *
 * Expected params:
 *   bootstrapServers — required; comma-separated broker list
 *   consumerGroup    — required for consumer connections; absent for producer-only
 *
 * The topic is taken from virtualNodeAddress, not from params.
 */
public class KafkaTransportFactory implements ItaraTransportFactory {

    @Override
    public String id() {
        return "kafka";
    }

    @Override
    public ItaraTransportConfig parseConfig(TransportConfig config) throws Exception {
        String bootstrapServers = config.getParams().get("bootstrapServers");
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException(
                    "[Itara/Kafka] Missing required param 'bootstrapServers' in transport config");
        }

        String consumerGroup = config.getParams().get("consumerGroup"); // nullable

        String topic = config.getVirtualNodeAddress();
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "[Itara/Kafka] Missing virtual node address (topic) in transport config");
        }

        String failureActionStr = config.getParams().get("failureAction");
        KafkaFailureAction failureAction = KafkaFailureAction.DROP;
        if (failureActionStr != null && !failureActionStr.isBlank()) {
            try {
                failureAction = KafkaFailureAction.valueOf(failureActionStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "[Itara/Kafka] Unknown failureAction '" + failureActionStr
                                + "'. Valid values: drop, dla, redeliver");
            }
        }

        String dlaTopic = config.getParams().get("dlaTopic");
        if (failureAction == KafkaFailureAction.DLA
                && (dlaTopic == null || dlaTopic.isBlank())) {
            throw new IllegalArgumentException(
                    "[Itara/Kafka] failureAction 'dla' requires param 'dlaTopic' to be set");
        }

        return new KafkaTransportConfig(
                bootstrapServers,
                consumerGroup,
                topic,
                config.isHandleTimeout(),
                failureAction,
                dlaTopic);
    }

    @Override
    public ItaraTransport create(ItaraTransportConfig config) throws Exception {
        return new KafkaTransport((KafkaTransportConfig) config);
    }
}
