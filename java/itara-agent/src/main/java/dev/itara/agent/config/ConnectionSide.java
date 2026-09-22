package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One side of a connection declaration: the {@code caller} or the
 * {@code callee} block of a {@link ConnectionEntry}.
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * connections:
 *   - id: "gateway-to-calculator"
 *     serializer:
 *       id: json
 *     callee:
 *       nodeId: "calculatorNode"
 *       transport:
 *         id: hardened-http
 *         params:
 *           host: "${CALC_HOST:-localhost}"
 *           port: "8081"
 *       authorization:
 *         id: rule-table
 *     caller:
 *       nodeId: "gatewayNode"
 *       transport:
 *         id: http
 *         params:
 *           host: "${CALC_HOST:-localhost}"
 *           port: "8081"
 *       failureSemantics:
 *         id: built-in
 *         maxRetry: 3
 * }</pre>
 *
 * <p>Both sides deliberately share this one class, and it carries every
 * plugin block — including ones that are only legal on the other side
 * (failureSemantics is caller-side only, authorization is callee-side
 * only). This class does not enforce that. It exists so that an illegal
 * placement, such as {@code authorization} under {@code caller}, still
 * parses into a real object that {@link ConnectionEntry#validate()} can
 * reject with a clear error. Were the field absent here, the
 * {@code ignoreUnknown} setting below would silently drop the block
 * instead, which is exactly what the placement rules forbid.
 *
 * <p>A plugin block declared here replaces the connection-level block of
 * the same kind for this side entirely — block-level replacement, never
 * field-level merging. This class only holds what was declared; the
 * fallback to the connection level happens in
 * {@link ConnectionEntry#resolveCaller()} and
 * {@link ConnectionEntry#resolveCallee()}.
 *
 * <p>Unknown fields are silently ignored — forward compatibility for
 * future fields, as everywhere else in the wiring config.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionSide {

    /** Required for deserialization. */
    public ConnectionSide() {}

    /**
     * The id of the node on this side of the connection. Mandatory on the
     * callee, and mandatory on the caller whenever a caller block is
     * present at all — an absent caller block, not a blank nodeId, is how
     * an external connection is expressed. Presence and character set are
     * checked by {@link ConnectionEntry#validate()}, not here.
     */
    private String nodeId;

    /** Side-specific transport; when absent, the connection-level one applies. */
    private TransportEntry transport;

    /** Side-specific serializer; when absent, the connection-level one applies. */
    private SerializerEntry serializer;

    /**
     * Failure semantics. Legal on the caller side only — retry and timeout
     * behavior is exclusively the caller's concern. Kept on this shared
     * class solely so an illegal callee-side declaration can be rejected.
     */
    private FailureSemanticsEntry failureSemantics;

    /** Side-specific authentication; when absent, the connection-level one applies. */
    private AuthenticationEntry authentication;

    /**
     * Authorization. Legal on the callee side only — access control is
     * purely the callee's own decision. Kept on this shared class solely
     * so an illegal caller-side declaration can be rejected.
     */
    private AuthorizationEntry authorization;

    /**
     * Returns the id of the node on this side of the connection.
     *
     * @return the node id, or null if not declared
     */
    public String getNodeId() { return nodeId; }
    /**
     * Sets the id of the node on this side of the connection.
     *
     * @param nodeId the node id
     */
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    /**
     * Returns this side's own transport block.
     *
     * @return this side's transport block, or null if not declared
     */
    public TransportEntry getTransport() { return transport; }
    /**
     * Sets this side's own transport block.
     *
     * @param transport this side's transport block
     */
    public void setTransport(TransportEntry transport) { this.transport = transport; }

    /**
     * Returns this side's own serializer block.
     *
     * @return this side's serializer block, or null if not declared
     */
    public SerializerEntry getSerializer() { return serializer; }
    /**
     * Sets this side's own serializer block.
     *
     * @param serializer this side's serializer block
     */
    public void setSerializer(SerializerEntry serializer) { this.serializer = serializer; }

    /**
     * Returns this side's failure semantics block. Only legal on the caller side.
     *
     * @return this side's failure semantics block, or null if not declared
     */
    public FailureSemanticsEntry getFailureSemantics() { return failureSemantics; }
    /**
     * Sets this side's failure semantics block. Only legal on the caller side.
     *
     * @param failureSemantics this side's failure semantics block
     */
    public void setFailureSemantics(FailureSemanticsEntry failureSemantics) {
        this.failureSemantics = failureSemantics;
    }

    /**
     * Returns this side's own authentication block.
     *
     * @return this side's authentication block, or null if not declared
     */
    public AuthenticationEntry getAuthentication() { return authentication; }
    /**
     * Sets this side's own authentication block.
     *
     * @param authentication this side's authentication block
     */
    public void setAuthentication(AuthenticationEntry authentication) {
        this.authentication = authentication;
    }

    /**
     * Returns this side's authorization block. Only legal on the callee side.
     *
     * @return this side's authorization block, or null if not declared
     */
    public AuthorizationEntry getAuthorization() { return authorization; }
    /**
     * Sets this side's authorization block. Only legal on the callee side.
     *
     * @param authorization this side's authorization block
     */
    public void setAuthorization(AuthorizationEntry authorization) {
        this.authorization = authorization;
    }

    @Override
    public String toString() {
        return "ConnectionSide{nodeId='" + nodeId
                + "', transport.id='" + (transport != null ? transport.getId() : null)
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', failureSemantics.id='" + (failureSemantics != null ? failureSemantics.getId() : null)
                + "', authentication.id='" + (authentication != null ? authentication.getId() : null)
                + "', authorization.id='" + (authorization != null ? authorization.getId() : null)
                + "'}";
    }
}
