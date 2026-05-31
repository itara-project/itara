package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

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

    private List<NodeEntry> nodes = new ArrayList<>();
    private List<ConnectionEntry> connections = new ArrayList<>();
    private List<String> localNodeIds = new ArrayList<>();

    public List<NodeEntry> getNodes() { return nodes; }
    public void setNodes(List<NodeEntry> nodes) {
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

    public void validate() {
        if (nodes != null) nodes.forEach(NodeEntry::validate);
        if (connections != null) connections.forEach(ConnectionEntry::validate);
    }

    public String getComponentOfNodeId(String nodeId) {
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).map(NodeEntry::getComponent)
                .findFirst()
                .orElseThrow( () -> new IllegalStateException("Cannot find node entry for nodeId " + nodeId));
    }

    public boolean isNodeLocal(NodeEntry nodeEntry) {
        return localNodeIds.contains(nodeEntry.getId());
    }

    public List<NodeEntry> getLocalNodes() {
        return nodes.stream().filter(this::isNodeLocal).toList();
    }
}
