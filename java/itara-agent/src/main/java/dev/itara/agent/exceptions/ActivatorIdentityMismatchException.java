package dev.itara.agent.exceptions;

/**
 * Thrown in isolated mode when a component's directory contains exactly
 * one activator, but for a different component than the directory's name
 * implies.
 */
public class ActivatorIdentityMismatchException extends ActivatorScanException {

    private final String expectedComponentId;
    private final String actualComponentId;

    /**
     * @param expectedComponentId the component id implied by the directory name
     * @param actualComponentId   the component id the activator was actually for
     * @param directoryPath       the directory that was scanned
     */
    public ActivatorIdentityMismatchException(String expectedComponentId, String actualComponentId, String directoryPath) {
        super("[Itara] Directory for component '" + expectedComponentId + "' at " + directoryPath
                + " contains an activator for a different component ('" + actualComponentId
                + "'). Check that the correct jars are present and that the directory name "
                + "matches the component id.");
        this.expectedComponentId = expectedComponentId;
        this.actualComponentId = actualComponentId;
    }

    /** @return the component id implied by the directory name */
    public String getExpectedComponentId() {
        return expectedComponentId;
    }

    /** @return the component id the activator was actually for */
    public String getActualComponentId() {
        return actualComponentId;
    }
}
