package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.regex.Pattern;

/**
 * Base type for all node declarations in the wiring configuration.
 *
 * <p>The `kind` field is the discriminator. When absent, COMPONENT is
 * assumed for backwards compatibility with existing wiring configs.
 *
 * <p>Each subtype provides:
 * <ul>
 * <li>contractIdentifier() — the id used to look up the contract class
 * and register the proxy or dispatcher</li>
 * <li>kind — typed discriminator for switch statements</li>
 * </ul>
 *
 * <p>See spec §4.3.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "kind",
        defaultImpl = ComponentNode.class,
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ComponentNode.class, name = "component"),
        @JsonSubTypes.Type(value = VirtualNode.class,   name = "virtual")
})
public abstract class Node {

    /** Constructed only by subclasses. */
    protected Node() {}

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private String id;
    private NodeKind kind;

    /**
     * Returns this node's own identifier.
     *
     * @return this node's own identifier
     */
    public String getId()          { return id; }
    /**
     * Sets this node's own identifier.
     *
     * @param id this node's own identifier
     */
    public void setId(String id)   { this.id = id; }

    /**
     * Returns the discriminator for this node's type.
     *
     * @return the discriminator for this node's type
     */
    public NodeKind getKind()            { return kind; }
    /**
     * Sets the discriminator for this node's type.
     *
     * @param kind the discriminator for this node's type
     */
    public void setKind(NodeKind kind)   { this.kind = kind; }

    /**
     * Returns the contract identifier for this node.
     *
     * <p>For component nodes: the component id (e.g. "order-service").
     * For virtual nodes:   the full contract reference
     *                      (e.g. "order-events/order-placed").
     *
     * <p>Used by the agent to look up the contract class, register
     * proxies, and create dispatchers — without branching on node type.
     * @return this node's contract identifier
     */
    public abstract String contractIdentifier();

    /**
     * Validates this node's required fields.
     *
     * @throws ConfigurationException if any required field is missing or
     *         invalid
     */
    public abstract void validate();

    /**
     * Validates that {@code id} is present and matches the allowed
     * character set. Subclasses call this from their own {@link #validate}.
     */
    protected void validateId() {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Node is missing required field 'id'.");
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new ConfigurationException(
                    "[Itara] Node id='" + id + "' is invalid — only letters, digits, "
                            + "'.', '_', and '-' are allowed.");
        }
    }

    @Override
    public String toString() {
        return "Node{id='" + id + "', kind='" + kind + "'}";
    }
}
