package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.itara.spi.failuresemantics.FailureSemanticsConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A connection declared in the wiring configuration.
 *
 * Defines how one node calls another, including the transport
 * mechanism and any transport-specific properties.
 *
 * Example YAML:
 *
 *   connections:
 *     - id:   "gateway-to-calculator"
 *       from: "gateway"
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

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * This connection's own identifier. Required, and unique across every
     * connection in the wiring config (see WiringConfig.validate()) —
     * unlike 'from'/'to', which identify nodes, this identifies the
     * connection itself: the specific link between them, distinct from
     * any other connection that might share the same 'from' and 'to'
     * (different transport, different serializer, etc.) or the same
     * 'to' from a different caller.
     *
     * Case-sensitive. Letters, digits, '.', '_', and '-' only — this set
     * is deliberately conservative: it's exactly Kafka's own topic-name
     * character set, and it stays unencoded-safe in HTTP header values
     * and URL path segments, both of which a transport is free to use to
     * propagate it.
     */
    private String id;

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
     * Required serializer configuration for this connection, except for
     * direct (colocated) connections — a direct connection never crosses
     * a process boundary, so nothing ever serializes anything on it, and
     * validate() does not demand a serializer block for it. For every
     * other connection there is no serializer choice that is safe to
     * assume silently, so a missing block or a missing id within it is a
     * configuration error (see validate()).
     */
    private SerializerEntry serializer;

    /**
     * Optional failure semantics configuration for this connection.
     * Absent means the noop implementation is used — current behaviour.
     */
    private FailureSemanticsEntry failureSemantics;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public TransportEntry getTransport()             { return transport; }
    public void setTransport(TransportEntry transport){ this.transport = transport; }

    public SerializerEntry getSerializer()              { return serializer; }
    public void setSerializer(SerializerEntry serializer){ this.serializer = serializer; }

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
        return "ConnectionEntry{id='" + id + "', from='" + from + "', to='" + to
                + "', transport.id='" + (transport != null ? transport.getId() : null)
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null) + "'}";
    }

    public void validate() {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'id'.");
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is invalid — only letters, digits, "
                            + "'.', '_', and '-' are allowed.");
        }
        if (to == null || to.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'to'.");
        }
        if (transport == null || transport.getId() == null || transport.getId().isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'transport.id'.");
        }
        if (isDirect() && isExternal()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is direct but has no 'from' — direct "
                            + "connections are colocated, in-process calls and cannot be external.");
        }
        if (!isDirect() && (serializer == null || serializer.getId() == null || serializer.getId().isBlank())) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'serializer.id'.");
        }
    }

    public boolean isRelatedToAnyOfNodes(List<String> nodeIds) {
        //if it is a source of a communication
        if (from != null && !from.isBlank() && nodeIds.contains(from)) return true;
        if (to != null && !to.isBlank() && nodeIds.contains(to)) return true;

        return false;
    }
}
