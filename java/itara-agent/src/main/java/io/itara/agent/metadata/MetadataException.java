package io.itara.agent.metadata;

/**
 * Thrown when `.itara` metadata cannot be located, read, or parsed,
 * or when component identity cannot be resolved from it.
 *
 * Resolution failures here are treated as fatal — component identity
 * is required for the agent to start (see ItaraMetadataIndex and
 * ActivatorScanner).
 */
public class MetadataException extends RuntimeException {

    public MetadataException(String message) {
        super(message);
    }

    public MetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
