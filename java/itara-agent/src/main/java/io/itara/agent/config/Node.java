package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base type for all node declarations in the wiring configuration.
 *
 * The `kind` field is the discriminator. When absent, COMPONENT is
 * assumed for backwards compatibility with existing wiring configs.
 *
 * Each subtype provides:
 *   contractIdentifier() — the id used to look up the contract class
 *                          and register the proxy or dispatcher
 *   kind                 — typed discriminator for switch statements
 *
 * See spec §4.3.
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

    private String id;
    private NodeKind kind;

    public String getId()          { return id; }
    public void setId(String id)   { this.id = id; }

    public NodeKind getKind()            { return kind; }
    public void setKind(NodeKind kind)   { this.kind = kind; }

    /**
     * Returns the contract identifier for this node.
     *
     * For component nodes: the component id (e.g. "order-service").
     * For virtual nodes:   the full contract reference
     *                      (e.g. "order-events/order-placed").
     *
     * Used by the agent to look up the contract class, register
     * proxies, and create dispatchers — without branching on node type.
     */
    public abstract String contractIdentifier();

    public abstract void validate();

    @Override
    public String toString() {
        return "Node{id='" + id + "', kind='" + kind + "'}";
    }
}
