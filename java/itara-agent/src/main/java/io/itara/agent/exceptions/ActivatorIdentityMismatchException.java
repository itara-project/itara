package io.itara.agent.exceptions;

/**
 * Thrown in isolated mode when a component's directory contains exactly
 * one activator, but for a different component than the directory's name
 * implies.
 */
public class ActivatorIdentityMismatchException extends ActivatorScanException {

    private final String expectedComponentId;
    private final String actualComponentId;

    public ActivatorIdentityMismatchException(String expectedComponentId, String actualComponentId, String directoryPath) {
        super("[Itara] Directory for component '" + expectedComponentId + "' at " + directoryPath
                + " contains an activator for a different component ('" + actualComponentId
                + "'). Check that the correct jars are present and that the directory name "
                + "matches the component id.");
        this.expectedComponentId = expectedComponentId;
        this.actualComponentId = actualComponentId;
    }

    public String getExpectedComponentId() {
        return expectedComponentId;
    }

    public String getActualComponentId() {
        return actualComponentId;
    }
}
