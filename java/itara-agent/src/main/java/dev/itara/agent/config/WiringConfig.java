package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The per-JVM wiring configuration.
 *
 * <p>Loaded by the agent at startup from the file specified by
 * -Ditara.config=/path/to/wiring.yaml
 *
 * <p>Defines which nodes are present in this JVM slice and how
 * they connect to each other or to nodes in remote JVMs.
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * nodes:
 *   - id: "order-service-node"
 *     component: "order-service"
 *   - id: "pricing-service-node"
 *     component: "pricing-service"
 *
 * connections:
 *   - id:   "order-to-pricing"
 *     from: "order-service-node"
 *     to:   "pricing-service-node"
 *     transport:
 *       id: direct
 *
 *   - id:   "gateway-to-order"
 *     from: ""
 *     to:   "order-service-node"
 *     transport:
 *       id: http
 *       params:
 *         host: "${ORDER_HOST:-localhost}"
 *         port: "${ORDER_PORT:-8080}"
 *     serializer:
 *       id: json
 * }</pre>
 *
 * <p>Environment variable substitution is supported in all string values
 * using the syntax ${VAR_NAME:-default_value}. If the variable is not
 * set, the default value is used. If no default is provided and the
 * variable is not set, the placeholder is left as-is and a warning
 * is logged.
 *
 * <p>Unknown fields are silently ignored — this ensures forward compatibility
 * when newer config fields are introduced (e.g. timeout, retry) and an
 * older agent version reads the config.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WiringConfig {

    /** Constructs an empty wiring config. Populated via setters by Jackson. */
    public WiringConfig() {}

    private List<Node> nodes = new ArrayList<>();
    private List<ConnectionEntry> connections = new ArrayList<>();
    private List<String> localNodeIds = new ArrayList<>();

    /**
     * Returns every node declared in this config; never null.
     *
     * @return every node declared in this config; never null
     */
    public List<Node> getNodes() { return nodes; }
    /**
     * Sets every node declared in this config; null is treated as empty.
     *
     * @param nodes every node declared in this config; null is treated as empty
     */
    public void setNodes(List<Node> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    /**
     * Returns every connection declared in this config; never null.
     *
     * @return every connection declared in this config; never null
     */
    public List<ConnectionEntry> getConnections() { return connections; }
    /**
     * Sets every connection declared in this config; null is treated as empty.
     *
     * @param connections every connection declared in this config; null is treated as empty
     */
    public void setConnections(List<ConnectionEntry> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    /**
     * Returns the ids of the nodes local to this JVM slice.
     *
     * @return the ids of the nodes local to this JVM slice
     */
    public List<String> getLocalNodeIds() { return localNodeIds; }
    /**
     * Sets the ids of the nodes local to this JVM slice.
     *
     * @param localNodeIds the ids of the nodes local to this JVM slice
     */
    public void setLocalNodeIds(List<String> localNodeIds) {
        this.localNodeIds = localNodeIds;
    }

    /**
     * Returns every {@link ComponentNode} in {@link #getNodes()}.
     *
     * @return every {@link ComponentNode} in {@link #getNodes()}
     */
    public List<ComponentNode> componentNodes() {
        return nodes.stream()
                .filter(n -> n instanceof ComponentNode)
                .map(n -> (ComponentNode) n)
                .toList();
    }

    /**
     * Returns every {@link VirtualNode} in {@link #getNodes()}.
     *
     * @return every {@link VirtualNode} in {@link #getNodes()}
     */
    public List<VirtualNode> virtualNodes() {
        return nodes.stream()
                .filter(n -> n instanceof VirtualNode)
                .map(n -> (VirtualNode) n)
                .toList();
    }

    /**
     * Validates every node and connection, and connection id uniqueness
     * across the whole config.
     *
     * @throws ConfigurationException if any node, any connection, or the
     *         config as a whole is invalid
     */
    public void validate() {
        if (nodes != null) nodes.forEach(Node::validate);
        if (connections != null) connections.forEach(ConnectionEntry::validate);
        validateConnectionIdUniqueness();
    }

    /**
     * Connection ids are the key used to select the right dispatcher or
     * proxy for a connection at runtime (see DispatchKey) — a duplicate
     * here isn't a style nitpick, it's two connections that would be
     * indistinguishable at dispatch time.
     */
    private void validateConnectionIdUniqueness() {
        Set<String> seen = new HashSet<>();
        for (ConnectionEntry conn : connections) {
            if (!seen.add(conn.getId())) {
                throw new ConfigurationException(
                        "[Itara] Duplicate connection id '" + conn.getId()
                                + "' — connection ids must be unique across the whole wiring config.");
            }
        }
    }

    /**
     * Resolves a component node's id to the component id it's an instance of.
     *
     * @param nodeId a component node's id
     * @return the component id that node is an instance of
     * @throws IllegalStateException if no component node with this id exists
     */
    public String getComponentOfNodeId(String nodeId) {
        return componentNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .map(ComponentNode::getComponent)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[Itara] Cannot find component node for nodeId '" + nodeId + "'"));
    }

    /**
     * Returns the node with this id, or empty if none exists.
     *
     * @param nodeId the node id to look up
     * @return the node with this id, or empty if none exists
     */
    public Optional<Node> findNode(String nodeId) {
        return nodes.stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst();
    }

    /**
     * Returns the VirtualNodeEntry for the given node id, or empty if it is
     * a component node (or not present at all).
     *
     * @param nodeId the node id to look up
     * @return the virtual node with this id, or empty if none exists
     */
    public Optional<VirtualNode> findVirtualNode(String nodeId) {
        return findNode(nodeId)
                .filter(n -> n.getKind() == NodeKind.VIRTUAL)
                .map(n -> (VirtualNode) n);
    }

    /**
     * Returns true if the given node id refers to a virtual node.
     *
     * @param nodeId the node id to check
     * @return true if the given node id refers to a virtual node
     */
    public boolean isVirtualNode(String nodeId) {
        return findNode(nodeId)
                .map(n -> n.getKind() == NodeKind.VIRTUAL)
                .orElse(false);
    }

    /**
     * Returns true if this node's id is among {@link #getLocalNodeIds()}.
     *
     * @param node the node to check
     * @return true if this node's id is among {@link #getLocalNodeIds()}
     */
    public boolean isNodeLocal(Node node) {
        return localNodeIds.contains(node.getId());
    }

    /**
     * Returns every {@link ComponentNode} local to this JVM slice.
     *
     * @return every {@link ComponentNode} local to this JVM slice
     */
    public List<ComponentNode> getLocalNodes() {
        return componentNodes().stream()
                .filter(this::isNodeLocal)
                .toList();
    }
}
