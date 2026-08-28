package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.itara.spi.failuresemantics.FailureSemanticsConfig;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A connection declared in the wiring configuration.
 *
 * <p>Defines how one node calls another, including the transport
 * mechanism and any transport-specific properties.
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * connections:
 *   - id:   "gateway-to-calculator"
 *     from: "gateway"
 *     to:   "calculator"
 *     transport:
 *       id: http
 *       params:
 *         host: "${CALC_HOST:-localhost}"
 *         port: "${CALC_PORT:-8081}"
 *     serializer:
 *       id: json
 * }</pre>
 *
 * <p>The 'from' field may be absent or empty, indicating that the caller
 * is external to the Itara topology. This defines an inbound entry
 * point for the 'to' node.
 *
 * <p>Unknown fields are silently ignored — forward compatibility for
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

    private AuthenticationEntry authentication;
    private AuthorizationEntry authorization;

    /** @return this connection's own identifier */
    public String getId() { return id; }
    /** @param id this connection's own identifier */
    public void setId(String id) { this.id = id; }

    /** @return the calling node id, or null/blank if the caller is external */
    public String getFrom() { return from; }
    /** @param from the calling node id, or null/blank if the caller is external */
    public void setFrom(String from) { this.from = from; }

    /** @return the called node id */
    public String getTo() { return to; }
    /** @param to the called node id */
    public void setTo(String to) { this.to = to; }

    /** @return this connection's transport configuration */
    public TransportEntry getTransport()             { return transport; }
    /** @param transport this connection's transport configuration */
    public void setTransport(TransportEntry transport){ this.transport = transport; }

    /** @return this connection's serializer configuration, or null if not declared */
    public SerializerEntry getSerializer()              { return serializer; }
    /** @param serializer this connection's serializer configuration */
    public void setSerializer(SerializerEntry serializer){ this.serializer = serializer; }

    /** @return this connection's failure semantics configuration, or null if not declared */
    public FailureSemanticsEntry getFailureSemantics() { return failureSemantics; }
    /** @param failureSemantics this connection's failure semantics configuration */
    public void setFailureSemantics(FailureSemanticsEntry failureSemantics) {
        this.failureSemantics = failureSemantics;
    }

    /** @return this connection's authentication configuration, or null if not declared */
    public AuthenticationEntry getAuthentication() { return authentication; }
    /** @param authentication this connection's authentication configuration */
    public void setAuthentication(AuthenticationEntry authentication) { this.authentication = authentication; }

    /** @return this connection's authorization configuration, or null if not declared */
    public AuthorizationEntry getAuthorization() { return authorization; }
    /** @param authorization this connection's authorization configuration */
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
        return "ConnectionEntry{id='" + id + "', from='" + from + "', to='" + to
                + "', transport.id='" + (transport != null ? transport.getId() : null)
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', authentication.id='" + (authentication != null ? authentication.getId() : null)
                + "', authorization.id='" + (authorization != null ? authorization.getId() : null)
                + "'}";
    }

    /**
     * Validates all required fields on this connection.
     *
     * @throws ConfigurationException if any required field is missing or
     *         invalid
     */
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
        if (authentication != null) authentication.validate(to);
        if (authorization != null) authorization.validate(to);
    }

    /**
     * @param nodeIds the node ids to check against
     * @return true if this connection's 'from' or 'to' is among nodeIds
     */
    public boolean isRelatedToAnyOfNodes(List<String> nodeIds) {
        //if it is a source of a communication
        if (from != null && !from.isBlank() && nodeIds.contains(from)) return true;
        if (to != null && !to.isBlank() && nodeIds.contains(to)) return true;

        return false;
    }
}
