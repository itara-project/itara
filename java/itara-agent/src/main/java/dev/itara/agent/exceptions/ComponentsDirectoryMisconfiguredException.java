package dev.itara.agent.exceptions;

/**
 * Thrown when the components-directory environment variable is set but
 * does not point to an existing directory.
 */
public class ComponentsDirectoryMisconfiguredException extends ActivatorScanException {

    private final String envVarName;
    private final String configuredPath;

    /**
     * @param envVarName     the environment variable that was set
     * @param configuredPath the path it was set to
     */
    public ComponentsDirectoryMisconfiguredException(String envVarName, String configuredPath) {
        super("[Itara] " + envVarName + " is set to '" + configuredPath
                + "' but that path does not exist or is not a directory.");
        this.envVarName = envVarName;
        this.configuredPath = configuredPath;
    }

    /** @return the environment variable that was set */
    public String getEnvVarName() {
        return envVarName;
    }

    /** @return the path it was set to */
    public String getConfiguredPath() {
        return configuredPath;
    }
}
