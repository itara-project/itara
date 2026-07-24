package io.itara.agent.exceptions;

/**
 * Thrown in isolated mode when a local component's expected directory —
 * named after its component id — does not exist under the components root.
 */
public class ComponentDirectoryNotFoundException extends ActivatorScanException {

    private final String componentId;
    private final String expectedPath;

    public ComponentDirectoryNotFoundException(String componentId, String expectedPath) {
        super("[Itara] Expected directory for component '" + componentId + "' not found at "
                + expectedPath + ". In isolated mode, each local component's directory name "
                + "must exactly match its component id.");
        this.componentId = componentId;
        this.expectedPath = expectedPath;
    }

    public String getComponentId() {
        return componentId;
    }

    public String getExpectedPath() {
        return expectedPath;
    }
}
