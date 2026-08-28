package dev.itara.agent.config;

/**
 * Thrown when the wiring configuration is malformed or contains invalid values.
 *
 * <p>Distinct from IOException (which covers file read failures) — this exception
 * indicates that the file was readable but its content was invalid.
 *
 * <p>Always includes the source path and enough context to locate the problem
 * without reading the full stack trace.
 */
public class ConfigurationException extends RuntimeException {

    /** @param message description of the configuration error, with enough context to locate it */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * @param message description of the configuration error, with enough context to locate it
     * @param cause   the underlying cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
