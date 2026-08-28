package dev.itara.agent.exceptions;

/**
 * Thrown when no activator is found for a local component — in shared
 * mode, anywhere on the scanned classloader; in isolated mode, inside
 * that component's own directory.
 */
public class ActivatorNotFoundException extends ActivatorScanException {

    private final String componentId;

    /** @param componentId the component no activator was found for, in shared mode */
    public ActivatorNotFoundException(String componentId) {
        super("[Itara] No activator found for component '" + componentId + "'. "
                + "Check META-INF/itara/activator is present on the classpath.");
        this.componentId = componentId;
    }

    /**
     * @param componentId   the component no activator was found for, in isolated mode
     * @param directoryPath the component's own directory that was scanned
     */
    public ActivatorNotFoundException(String componentId, String directoryPath) {
        super("[Itara] No activator found for component '" + componentId + "' in directory "
                + directoryPath + ". Check META-INF/itara/activator in that component's jar.");
        this.componentId = componentId;
    }

    /** @return the component no activator was found for */
    public String getComponentId() {
        return componentId;
    }
}
