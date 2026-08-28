package dev.itara.agent;

import dev.itara.agent.metadata.MetadataFile;
import dev.itara.api.ItaraActivator;

/**
 * Result of activator discovery for a single local component: the
 * activator class to instantiate, plus the component identity (id,
 * version, api-version) resolved from its `.itara` metadata file.
 */
public class ActivatedComponent {

    private final Class<? extends ItaraActivator> activatorClass;
    private final MetadataFile metadata;

    /**
     * @param activatorClass the discovered activator class for this component
     * @param metadata       the component's own parsed `.itara` metadata file
     */
    public ActivatedComponent(Class<? extends ItaraActivator> activatorClass, MetadataFile metadata) {
        this.activatorClass = activatorClass;
        this.metadata = metadata;
    }

    /** @return the discovered activator class for this component */
    public Class<? extends ItaraActivator> getActivatorClass() {
        return activatorClass;
    }

    /** @return this component's id, from its `.itara` [artifact] section */
    public String getComponentId() {
        return metadata.getArtifact().getId();
    }

    /** @return this component's own version, from its `.itara` [artifact] section */
    public String getVersion() {
        return metadata.getArtifact().getVersion();
    }

    /** @return the API version this component was built against, from its `.itara` [artifact] section */
    public String getApiVersion() {
        return metadata.getArtifact().getApiVersion();
    }

    /** @return this component's full parsed `.itara` metadata file */
    public MetadataFile getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ActivatedComponent{activatorClass=" + activatorClass.getName()
                + ", componentId='" + getComponentId() + "'"
                + ", version='" + getVersion() + "'"
                + ", apiVersion='" + getApiVersion() + "'}";
    }
}
