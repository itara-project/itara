package dev.itara.agent.exceptions;

/**
 * Thrown in isolated mode when a component's own classloader yields more
 * than one activator — a component's directory must contain exactly one
 * component's jars.
 */
public class ActivatorCountException extends ActivatorScanException {

    private final String componentId;
    private final int foundCount;

    /**
     * @param componentId   the component whose directory was scanned
     * @param directoryPath the directory that was scanned
     * @param foundCount    the number of activators actually found
     */
    public ActivatorCountException(String componentId, String directoryPath, int foundCount) {
        super("[Itara] Expected exactly one activator for component '" + componentId
                + "' in directory " + directoryPath + ", found " + foundCount
                + ". A component's own classloader must contain exactly one component's jars "
                + "in isolated mode — check for missing or extra META-INF/itara/activator "
                + "descriptors under that directory.");
        this.componentId = componentId;
        this.foundCount = foundCount;
    }

    /** @return the component whose directory was scanned */
    public String getComponentId() {
        return componentId;
    }

    /** @return the number of activators actually found */
    public int getFoundCount() {
        return foundCount;
    }
}
