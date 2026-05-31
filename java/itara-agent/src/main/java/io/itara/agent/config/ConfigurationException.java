package io.itara.agent.config;

/**
 * Thrown when the wiring configuration is malformed or contains invalid values.
 *
 * Distinct from IOException (which covers file read failures) — this exception
 * indicates that the file was readable but its content was invalid.
 *
 * Always includes the source path and enough context to locate the problem
 * without reading the full stack trace.
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
