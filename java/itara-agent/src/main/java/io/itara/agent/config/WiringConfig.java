package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The per-JVM wiring configuration.
 *
 * Loaded by the agent at startup from the file specified by
 * -Ditara.config=/path/to/wiring.yaml
 *
 * Defines which nodes are present in this JVM slice and how
 * they connect to each other or to nodes in remote JVMs.
 *
 * Example YAML:
 *
 *   node:
 *     - id: "order-service-node"
 *       component: "order-service"
 *     - id: "pricing-service-node"
 *       component: "pricing-service"
 *
 *   connections:
 *     - from: "order-service-node"
 *       to:   "pricing-service-node"
 *       type: "direct"
 *
 *     - from: ""
 *       to:   "order-service-node"
 *       type: http
 *       host: "${ORDER_HOST:-localhost}"
 *       port: "${ORDER_PORT:-8080}"
 *       serializer: "json"
 *
 * Environment variable substitution is supported in all string values
 * using the syntax ${VAR_NAME:-default_value}. If the variable is not
 * set, the default value is used. If no default is provided and the
 * variable is not set, the placeholder is left as-is and a warning
 * is logged.
 *
 * Unknown fields are silently ignored — this ensures forward compatibility
 * when newer config fields are introduced (e.g. timeout, retry) and an
 * older agent version reads the config.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WiringConfig {

    private List<Node> nodes = new ArrayList<>();
    private List<ConnectionEntry> connections = new ArrayList<>();
    private List<String> localNodeIds = new ArrayList<>();

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    public List<ConnectionEntry> getConnections() { return connections; }
    public void setConnections(List<ConnectionEntry> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    public List<String> getLocalNodeIds() { return localNodeIds; }
    public void setLocalNodeIds(List<String> localNodeIds) {
        this.localNodeIds = localNodeIds;
    }

    public List<ComponentNode> componentNodes() {
        return nodes.stream()
                .filter(n -> n instanceof ComponentNode)
                .map(n -> (ComponentNode) n)
                .toList();
    }

    public List<VirtualNode> virtualNodes() {
        return nodes.stream()
                .filter(n -> n instanceof VirtualNode)
                .map(n -> (VirtualNode) n)
                .toList();
    }

    public void validate() {
        if (nodes != null) nodes.forEach(Node::validate);
        if (connections != null) connections.forEach(ConnectionEntry::validate);
    }

    public String getComponentOfNodeId(String nodeId) {
        return componentNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .map(ComponentNode::getComponent)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[Itara] Cannot find component node for nodeId '" + nodeId + "'"));
    }

    public Optional<Node> findNode(String nodeId) {
        return nodes.stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst();
    }

    /**
     * Returns the VirtualNodeEntry for the given node id, or empty if it is
     * a component node (or not present at all).
     */
    public Optional<VirtualNode> findVirtualNode(String nodeId) {
        return findNode(nodeId)
                .filter(n -> n.getKind() == NodeKind.VIRTUAL)
                .map(n -> (VirtualNode) n);
    }

    /**
     * Returns true if the given node id refers to a virtual node.
     */
    public boolean isVirtualNode(String nodeId) {
        return findNode(nodeId)
                .map(n -> n.getKind() == NodeKind.VIRTUAL)
                .orElse(false);
    }

    public boolean isNodeLocal(Node node) {
        return localNodeIds.contains(node.getId());
    }

    public List<ComponentNode> getLocalNodes() {
        return componentNodes().stream()
                .filter(this::isNodeLocal)
                .toList();
    }
}
