package dev.itara.agent.exceptions;

/**
 * Base type for all failures encountered while resolving activators for
 * local components — missing or misconfigured directories, missing or
 * ambiguous activator discovery, and identity mismatches between a
 * component's directory name and what was actually found inside it.
 */
public class ActivatorScanException extends RuntimeException {

    /** @param message description of the scan failure */
    public ActivatorScanException(String message) {
        super(message);
    }

    /**
     * @param message description of the scan failure
     * @param cause   the underlying cause
     */
    public ActivatorScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
