package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.itara.spi.failuresemantics.FailureSemanticsConfig;

import java.util.List;

/**
 * A connection declared in the wiring configuration.
 *
 * Defines how one node calls another, including the transport
 * mechanism and any transport-specific properties.
 *
 * Example YAML:
 *
 *   connections:
 *     - from: "gateway"
 *       to:   "calculator"
 *       transport:
 *         id: http
 *         params:
 *           host: "${CALC_HOST:-localhost}"
 *           port: "${CALC_PORT:-8081}"
 *       serializer: json
 *
 * The 'from' field may be absent or empty, indicating that the caller
 * is external to the Itara topology. This defines an inbound entry
 * point for the 'to' node.
 *
 * Unknown fields are silently ignored — forward compatibility for
 * future fields such as timeout and retry configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionEntry {

    /**
     * The calling node id. Absent or empty means the caller is
     * external — this connection exposes the 'to' component as an
     * inbound endpoint.
     */
    private String from;

    /** The called node id. Required. */
    private String to;


    private TransportEntry transport;

    /**
     * The serializer type for this connection.
     * Defaults to "json" if not specified.
     * Must match the type() identifier of an ItaraSerializer
     * implementation present in itara.lib.dir.
     */
    @JsonSetter(nulls = Nulls.SKIP)  // keep field default if YAML value is null
    private String serializer = "json";

    /**
     * Optional failure semantics configuration for this connection.
     * Absent means the noop implementation is used — current behaviour.
     */
    private FailureSemanticsEntry failureSemantics;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public TransportEntry getTransport()             { return transport; }
    public void setTransport(TransportEntry transport){ this.transport = transport; }

    public String getSerializer() { return serializer; }
    public void setSerializer(String serializer) {
        this.serializer = (serializer == null || serializer.isBlank())
                ? "json" : serializer.strip();
    }

    public FailureSemanticsEntry getFailureSemantics() { return failureSemantics; }
    public void setFailureSemantics(FailureSemanticsEntry failureSemantics) {
        this.failureSemantics = failureSemantics;
    }

    /**
     * Returns the failure semantics type id for this connection.
     * Defaults to "noop" if no failureSemantics block is declared.
     */
    public String getFailureSemanticsType() {
        return failureSemantics != null ? failureSemantics.getId() : "noop";
    }

    /**
     * Translates the failureSemantics block into the SPI config.
     * Returns an empty config if no block is declared.
     */
    public FailureSemanticsConfig getFailureSemanticsConfig() {
        return failureSemantics != null
                ? failureSemantics.toSpiConfig()
                : FailureSemanticsConfig.builder().build();
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
        return "direct".equalsIgnoreCase(transport.getId());
    }

    @Override
    public String toString() {
        return "ConnectionEntry{from='" + from + "', to='" + to
                + "', transport.id='" + (transport != null ? transport.getId() : null)
                + "', serializer='" + serializer + "'}";
    }

    public void validate() {
        if (to == null || to.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'to'.");
        }
        if (transport == null || transport.getId() == null || transport.getId().isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'transport.id'.");
        }
    }

    public boolean isRelatedToAnyOfNodes(List<String> nodeIds) {
        //if it is a source of a communication
        if (from != null && !from.isBlank() && nodeIds.contains(from)) return true;
        if (to != null && !to.isBlank() && nodeIds.contains(to)) return true;

        return false;
    }
}
