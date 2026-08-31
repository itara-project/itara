package dev.itara.transport.kafka;

public enum KafkaFailureAction {
    /** Discard the message silently after failed dispatch. Default. */
    DROP,
    /** Publish the failed message to the configured dead letter topic. */
    DLA,
    /**
     * Do not acknowledge — allow Kafka's redelivery mechanism to retry.
     *
     * Warning: if the message cannot be processed successfully, this will
     * result in indefinite redelivery. Use only when the failure is expected
     * to be transient. For poison pill protection, implement a retry counter
     * and fall back to DLA after N attempts — this is not handled automatically.
     */
    REDELIVER
}
