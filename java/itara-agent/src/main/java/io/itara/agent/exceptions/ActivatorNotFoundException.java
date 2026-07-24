package io.itara.agent.exceptions;

/**
 * Thrown when no activator is found for a local component — in shared
 * mode, anywhere on the scanned classloader; in isolated mode, inside
 * that component's own directory.
 */
public class ActivatorNotFoundException extends ActivatorScanException {

    private final String componentId;

    public ActivatorNotFoundException(String componentId) {
        super("[Itara] No activator found for component '" + componentId + "'. "
                + "Check META-INF/itara/activator is present on the classpath.");
        this.componentId = componentId;
    }

    public ActivatorNotFoundException(String componentId, String directoryPath) {
        super("[Itara] No activator found for component '" + componentId + "' in directory "
                + directoryPath + ". Check META-INF/itara/activator in that component's jar.");
        this.componentId = componentId;
    }

    public String getComponentId() {
        return componentId;
    }
}
