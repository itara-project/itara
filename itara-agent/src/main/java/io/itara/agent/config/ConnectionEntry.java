package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * A connection declared in the wiring configuration.
 *
 * Defines how one component calls another, including the transport
 * mechanism and any transport-specific properties.
 *
 * Example YAML:
 *
 *   connections:
 *     - from: "gateway"
 *       to:   "calculator"
 *       type: http
 *       host: "${CALC_HOST:-localhost}"
 *       port: "${CALC_PORT:-8081}"
 *       serializer: json
 *
 * The 'from' field may be absent or empty, indicating that the caller
 * is external to the Itara topology. This defines an inbound entry
 * point for the 'to' component.
 *
 * Unknown fields are silently ignored — forward compatibility for
 * future fields such as timeout and retry configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionEntry {

    /**
     * The calling component id. Absent or empty means the caller is
     * external — this connection exposes the 'to' component as an
     * inbound endpoint.
     */
    private String from;

    /** The called component id. Required. */
    private String to;

    /**
     * The connection type. Required.
     * Supported values depend on which transport jars are in itara.lib.dir.
     * Built-in: "direct", "http", "kafka".
     */
    private String type;

    /**
     * The hostname of the remote JVM.
     * Required for non-direct connections.
     * Supports environment variable substitution: ${HOST:-localhost}
     */
    private String host;

    /**
     * The port of the remote JVM.
     * Required for non-direct connections.
     * Supports environment variable substitution: ${PORT:-8080}
     */
    private int port;

    /**
     * The serializer type for this connection.
     * Defaults to "json" if not specified.
     * Must match the type() identifier of an ItaraSerializer
     * implementation present in itara.lib.dir.
     */
    @JsonSetter(nulls = Nulls.SKIP)  // keep field default if YAML value is null
    private String serializer = "json";

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getSerializer() { return serializer; }
    public void setSerializer(String serializer) {
        this.serializer = (serializer == null || serializer.isBlank())
                ? "json" : serializer.strip();
    }

    /**
     * Returns true if the caller is external to the Itara topology.
     */
    public boolean isExternal() {
        return from == null || from.isBlank();
    }

    /**
     * Returns true if this is a direct (colocated, in-process) connection.
     */
    public boolean isDirect() {
        return "direct".equalsIgnoreCase(type);
    }

    /**
     * Returns true if this is an HTTP connection.
     */
    public boolean isHttp() {
        return "http".equalsIgnoreCase(type);
    }

    @Override
    public String toString() {
        return "ConnectionEntry{from='" + from + "', to='" + to
                + "', type='" + type + "', serializer='" + serializer + "'}";
    }

    public void validate() {
        if (to == null || to.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'to'.");
        }
        if (type == null || type.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'type'.");
        }
        if (!isDirect()) {
            if (port <= 0) {
                throw new ConfigurationException(
                        "[Itara] Connection to='" + to + "' of type '" + type
                                + "' is missing required field 'port'.");
            }
        }
        // host is not validated here — whether host is required depends on
        // whether this JVM is the caller or the callee, which is determined
        // by the agent after classpath scanning, not by the config loader.
    }
}
