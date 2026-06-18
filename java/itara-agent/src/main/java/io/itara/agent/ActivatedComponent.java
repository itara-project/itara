package io.itara.agent;

import io.itara.agent.metadata.MetadataFile;
import io.itara.api.ItaraActivator;

/**
 * Result of activator discovery for a single local component: the
 * activator class to instantiate, plus the component identity (id,
 * version, api-version) resolved from its `.itara` metadata file.
 *
 * Replaces the bare activator-class value that ActivatorScanner used to
 * return — component identity now comes from `.itara`, not from
 * META-INF/itara/activator (see component-identity-from-.itara issue).
 */
public class ActivatedComponent {

    private final Class<? extends ItaraActivator> activatorClass;
    private final MetadataFile metadata;

    public ActivatedComponent(Class<? extends ItaraActivator> activatorClass, MetadataFile metadata) {
        this.activatorClass = activatorClass;
        this.metadata = metadata;
    }

    public Class<? extends ItaraActivator> getActivatorClass() {
        return activatorClass;
    }

    public String getComponentId() {
        return metadata.getArtifact().getId();
    }

    public String getVersion() {
        return metadata.getArtifact().getVersion();
    }

    public String getApiVersion() {
        return metadata.getArtifact().getApiVersion();
    }

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
