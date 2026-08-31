package dev.itara.agent;

import dev.itara.agent.metadata.MetadataFile;
import dev.itara.api.ItaraActivator;

/**
 * Result of activator discovery for a single local component: the
 * activator class to instantiate, plus the component identity (id,
 * version, api-version) resolved from its `.itara` metadata file.
 *
 * <p>Replaces the bare activator-class value that ActivatorScanner used to
 * return — component identity now comes from `.itara`, not from
 * META-INF/itara/activator (see component-identity-from-.itara issue).
 */
public class ActivatedComponent {

    private final Class<? extends ItaraActivator> activatorClass;
    private final MetadataFile metadata;

    /**
     * Constructs an activated-component record.
     *
     * @param activatorClass the discovered activator class for this component
     * @param metadata       the component's own parsed `.itara` metadata file
     */
    public ActivatedComponent(Class<? extends ItaraActivator> activatorClass, MetadataFile metadata) {
        this.activatorClass = activatorClass;
        this.metadata = metadata;
    }

    /**
     * Returns the discovered activator class for this component.
     *
     * @return the discovered activator class for this component
     */
    public Class<? extends ItaraActivator> getActivatorClass() {
        return activatorClass;
    }

    /**
     * Returns this component's id, from its `.itara` [artifact] section.
     *
     * @return this component's id, from its `.itara` [artifact] section
     */
    public String getComponentId() {
        return metadata.getArtifact().getId();
    }

    /**
     * Returns this component's own version, from its `.itara` [artifact] section.
     *
     * @return this component's own version, from its `.itara` [artifact] section
     */
    public String getVersion() {
        return metadata.getArtifact().getVersion();
    }

    /**
     * Returns the API version this component was built against, from its `.itara` [artifact] section.
     *
     * @return the API version this component was built against, from its `.itara` [artifact] section
     */
    public String getApiVersion() {
        return metadata.getArtifact().getApiVersion();
    }

    /**
     * Returns this component's full parsed `.itara` metadata file.
     *
     * @return this component's full parsed `.itara` metadata file
     */
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
