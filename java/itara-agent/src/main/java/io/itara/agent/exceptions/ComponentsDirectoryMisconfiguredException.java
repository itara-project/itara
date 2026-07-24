package io.itara.agent.exceptions;

/**
 * Thrown when the components-directory environment variable is set but
 * does not point to an existing directory.
 */
public class ComponentsDirectoryMisconfiguredException extends ActivatorScanException {

    private final String envVarName;
    private final String configuredPath;

    public ComponentsDirectoryMisconfiguredException(String envVarName, String configuredPath) {
        super("[Itara] " + envVarName + " is set to '" + configuredPath
                + "' but that path does not exist or is not a directory.");
        this.envVarName = envVarName;
        this.configuredPath = configuredPath;
    }

    public String getEnvVarName() {
        return envVarName;
    }

    public String getConfiguredPath() {
        return configuredPath;
    }
}
