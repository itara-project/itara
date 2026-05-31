package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A node declared in the wiring configuration.
 *
 * A node entry tells the agent that a node with this id
 * is expected to be present in this JVM slice. The agent will scan
 * the classpath for a matching activator via META-INF/itara/activator.
 *
 * Example YAML:
 *
 *   nodes:
 *     - id: "calculatorNode"
 *       component: "calculator"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeEntry {

    private String id;
    /**
     * The component. Must match the id declared in the
     * @ComponentInterface annotation on the contract interface.
     */
    private String component;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }

    @Override
    public String toString() {
        return "NodeEntry{id='" + id + "', component='" + component + "'}";
    }

    public void validate() {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Node entry is missing required field 'id'.");
        }
        if (component == null || component.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Node entry is missing required field 'component'.");
        }
    }
}
