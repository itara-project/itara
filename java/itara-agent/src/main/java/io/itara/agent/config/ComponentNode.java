package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A component node — a deployable component with an activator and a
 * contract interface.
 *
 * `kind` is optional. When absent, component is assumed.
 *
 * Example YAML:
 *   nodes:
 *     - id: "orderServiceNode"
 *       component: "order-service"
 *
 *     - id: "inventoryNode"
 *       kind: component
 *       component: "inventory"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentNode extends Node {

    /**
     * The component id. Must match the id declared in the
     * @ComponentInterface annotation on the contract interface.
     */
    private String component;

    public ComponentNode() {
        setKind(NodeKind.COMPONENT);
    }

    public String getComponent()               { return component; }
    public void setComponent(String component) { this.component = component; }

    /**
     * Returns the component id — used as the registry key for proxies
     * and dispatchers, and to look up the contract class.
     */
    @Override
    public String contractIdentifier() {
        return component;
    }

    @Override
    public void validate() {
        validateId();
        if (component == null || component.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Component node '" + getId()
                            + "' is missing required field 'component'.");
        }
    }

    @Override
    public String toString() {
        return "ComponentNode{id='" + getId() + "', component='" + component + "'}";
    }
}
