package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    private AuthenticationEntry authentication;
    private AuthorizationEntry authorization;

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

    public AuthenticationEntry getAuthentication() { return authentication; }
    public void setAuthentication(AuthenticationEntry authentication) { this.authentication = authentication; }

    public AuthorizationEntry getAuthorization() { return authorization; }
    public void setAuthorization(AuthorizationEntry authorization) { this.authorization = authorization; }

    /**
     * Returns the failure semantics type id for this connection.
     * Defaults to "noop" if no failureSemantics block is declared.
     */
    public String getFailureSemanticsId() {
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
     * Returns the authentication type id for this connection.
     * Defaults to "noop" if no authentication block is declared.
     */
    public String getAuthenticationId() {
        return authentication != null ? authentication.getId() : "noop";
    }

    /**
     * Returns the authorization type id for this connection.
     * Defaults to "noop" if no authorization block is declared.
     */
    public String getAuthorizationId() {
        return authorization != null ? authorization.getId() : "noop";
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
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', authentication.id='" + (authentication != null ? authentication.getId() : null)
                + "', authorization.id='" + (authorization != null ? authorization.getId() : null)
                + "'}";
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
        if (!isDirect() && (serializer == null || serializer.getId() == null || serializer.getId().isBlank())) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' is missing required field 'serializer.id'.");
        }
        if (isDirect() && authentication != null && !"noop".equalsIgnoreCase(authentication.getId())) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' declares authentication.id='" + authentication.getId()
                            + "' on a direct connection. Authentication and authorization are not yet enforced "
                            + "for direct (colocated) connections — see the tracking issue. Remove the "
                            + "authentication block, or use a non-direct transport if enforcement is required.");
        }
        if (isDirect() && authorization != null && !"noop".equalsIgnoreCase(authorization.getId())) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + to + "' declares authorization.id='" + authorization.getId()
                            + "' on a direct connection. Authentication and authorization are not yet enforced "
                            + "for direct (colocated) connections — see the tracking issue. Remove the "
                            + "authorization block, or use a non-direct transport if enforcement is required.");
        }
    }

    public boolean isRelatedToAnyOfNodes(List<String> nodeIds) {
        //if it is a source of a communication
        if (from != null && !from.isBlank() && nodeIds.contains(from)) return true;
        if (to != null && !to.isBlank() && nodeIds.contains(to)) return true;

        return false;
    }
}
