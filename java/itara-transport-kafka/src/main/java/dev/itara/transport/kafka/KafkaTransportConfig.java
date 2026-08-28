package dev.itara.transport.kafka;

import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.spi.transport.ItaraTransportGroupingKey;

import java.util.Objects;

/**
 * Parsed configuration for a single Kafka transport connection.
 *
 * Grouping key is bootstrapServers + consumerGroup — two connections
 * to the same cluster with the same consumer group share one KafkaConsumer
 * instance and subscribe to all their topics together.
 *
 * consumerGroup is null for producer-only connections (send side).
 * topic comes from the virtual node address.
 */
public final class KafkaTransportConfig implements ItaraTransportConfig, ItaraTransportGroupingKey {

    private final String bootstrapServers;
    private final String consumerGroup;
    private final String topic;
    private final boolean handleTimeout;
    private final KafkaFailureAction failureAction;
    private final String dlaTopic;  // nullable, only used when failureAction = DLA

    public KafkaTransportConfig(String bootstrapServers,
                                String consumerGroup,
                                String topic,
                                boolean handleTimeout,
                                KafkaFailureAction failureAction,
                                String dlaTopic) {
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup    = consumerGroup;
        this.topic            = topic;
        this.handleTimeout    = handleTimeout;
        this.failureAction    = failureAction != null ? failureAction : KafkaFailureAction.DROP;
        this.dlaTopic         = dlaTopic;
    }

    public String getBootstrapServers()          { return bootstrapServers; }
    public String getConsumerGroup()             { return consumerGroup; }
    public String getTopic()                     { return topic; }
    public boolean isHandleTimeout()             { return handleTimeout; }
    public KafkaFailureAction getFailureAction() { return failureAction; }
    public String getDlaTopic()                  { return dlaTopic; }

    @Override
    public ItaraTransportGroupingKey groupingKey() {
        return this;
    }

    /**
     * Two Kafka connections share an instance when they target the same
     * cluster and consumer group. Producer-only connections (null group)
     * group by cluster alone.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KafkaTransportConfig)) return false;
        KafkaTransportConfig that = (KafkaTransportConfig) other;
        return Objects.equals(this.bootstrapServers, that.bootstrapServers)
                && Objects.equals(this.consumerGroup, that.consumerGroup)
                && this.failureAction == that.failureAction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bootstrapServers, consumerGroup, failureAction);
    }
}
