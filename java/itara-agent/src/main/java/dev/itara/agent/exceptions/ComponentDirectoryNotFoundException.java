package dev.itara.agent.exceptions;

/**
 * Thrown in isolated mode when a local component's expected directory —
 * named after its component id — does not exist under the components root.
 */
public class ComponentDirectoryNotFoundException extends ActivatorScanException {

    /** The component whose directory was expected. */
    private final String componentId;
    /** The path that was expected to exist. */
    private final String expectedPath;

    /**
     * Constructs a directory-not-found exception.
     *
     * @param componentId  the component whose directory was expected
     * @param expectedPath the path that was expected to exist
     */
    public ComponentDirectoryNotFoundException(String componentId, String expectedPath) {
        super("[Itara] Expected directory for component '" + componentId + "' not found at "
                + expectedPath + ". In isolated mode, each local component's directory name "
                + "must exactly match its component id.");
        this.componentId = componentId;
        this.expectedPath = expectedPath;
    }

    /**
     * Returns the component whose directory was expected.
     *
     * @return the component whose directory was expected
     */
    public String getComponentId() {
        return componentId;
    }

    /**
     * Returns the path that was expected to exist.
     *
     * @return the path that was expected to exist
     */
    public String getExpectedPath() {
        return expectedPath;
    }
}
