package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A component node — a deployable component with an activator and a
 * contract interface.
 *
 * <p>`kind` is optional. When absent, component is assumed.
 *
 * <p>Example YAML:
 * <pre>{@code
 * nodes:
 *   - id: "orderServiceNode"
 *     component: "order-service"
 *
 *   - id: "inventoryNode"
 *     kind: component
 *     component: "inventory"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentNode extends Node {

    /**
     * The component id. Must match the id declared in the
     * {@code @ComponentInterface} annotation on the contract interface.
     */
    private String component;

    /** Constructs a component node, defaulting {@code kind} to {@link NodeKind#COMPONENT}. */
    public ComponentNode() {
        setKind(NodeKind.COMPONENT);
    }

    /**
     * Returns the component id this node is an instance of.
     *
     * @return the component id this node is an instance of
     */
    public String getComponent()               { return component; }
    /**
     * Sets the component id this node is an instance of.
     *
     * @param component the component id this node is an instance of
     */
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
