package dev.itara.agent.metadata;

/**
 * Thrown when `.itara` metadata cannot be located, read, or parsed,
 * or when component identity cannot be resolved from it.
 *
 * <p>Resolution failures here are treated as fatal — component identity
 * is required for the agent to start (see ItaraMetadataIndex and
 * ActivatorScanner).
 */
public class MetadataException extends RuntimeException {

    /** @param message description of the metadata resolution failure */
    public MetadataException(String message) {
        super(message);
    }

    /**
     * @param message description of the metadata resolution failure
     * @param cause   the underlying cause
     */
    public MetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
