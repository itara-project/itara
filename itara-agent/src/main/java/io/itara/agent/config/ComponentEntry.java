package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A component declared in the wiring configuration.
 *
 * A component entry tells the agent that a component with this id
 * is expected to be present in this JVM slice. The agent will scan
 * the classpath for a matching activator via META-INF/itara/activator.
 *
 * Example YAML:
 *
 *   components:
 *     - id: "calculator"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentEntry {

    /**
     * The component id. Must match the id declared in the
     * @ComponentInterface annotation on the contract interface.
     */
    private String id;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @Override
    public String toString() {
        return "ComponentEntry{id='" + id + "'}";
    }

    public void validate() {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Component entry is missing required field 'id'.");
        }
    }
}
