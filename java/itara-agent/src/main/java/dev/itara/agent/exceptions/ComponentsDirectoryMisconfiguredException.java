package dev.itara.agent.exceptions;

/**
 * Thrown when the components-directory environment variable is set but
 * does not point to an existing directory.
 */
public class ComponentsDirectoryMisconfiguredException extends ActivatorScanException {

    /** The environment variable that was set. */
    private final String envVarName;
    /** The path it was set to. */
    private final String configuredPath;

    /**
     * Constructs a misconfiguration exception.
     *
     * @param envVarName     the environment variable that was set
     * @param configuredPath the path it was set to
     */
    public ComponentsDirectoryMisconfiguredException(String envVarName, String configuredPath) {
        super("[Itara] " + envVarName + " is set to '" + configuredPath
                + "' but that path does not exist or is not a directory.");
        this.envVarName = envVarName;
        this.configuredPath = configuredPath;
    }

    /**
     * Returns the environment variable that was set.
     *
     * @return the environment variable that was set
     */
    public String getEnvVarName() {
        return envVarName;
    }

    /**
     * Returns the path it was set to.
     *
     * @return the path it was set to
     */
    public String getConfiguredPath() {
        return configuredPath;
    }
}
