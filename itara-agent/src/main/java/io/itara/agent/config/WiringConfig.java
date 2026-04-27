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
 * Defines which components are present in this JVM slice and how
 * they connect to each other or to components in remote JVMs.
 *
 * Example YAML:
 *
 *   components:
 *     - id: "order-service"
 *
 *   connections:
 *     - from: "order-service"
 *       to:   "pricing-service"
 *       type: direct
 *
 *     - from: ""
 *       to:   "order-service"
 *       type: http
 *       host: "${ORDER_HOST:-localhost}"
 *       port: "${ORDER_PORT:-8080}"
 *       serializer: json
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

    private List<ComponentEntry> components = new ArrayList<>();
    private List<ConnectionEntry> connections = new ArrayList<>();

    public List<ComponentEntry> getComponents() { return components; }
    public void setComponents(List<ComponentEntry> components) {
        this.components = components != null ? components : new ArrayList<>();
    }

    public List<ConnectionEntry> getConnections() { return connections; }
    public void setConnections(List<ConnectionEntry> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    public void validate() {
        if (components != null) components.forEach(ComponentEntry::validate);
        if (connections != null) connections.forEach(ConnectionEntry::validate);
    }
}
