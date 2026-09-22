package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A connection declared in the wiring configuration.
 *
 * <p>Defines how one node calls another. A connection has a mandatory
 * {@code callee} block and an optional {@code caller} block, each a
 * {@link ConnectionSide}; plugin blocks may be declared on the connection
 * itself (shared by both sides) or inside either side block.
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
 *
 *   - id: "external-to-gateway"
 *     callee:
 *       nodeId: "gatewayNode"
 *       transport:
 *         id: http
 *         params:
 *           port: "8082"
 *       serializer:
 *         id: json
 *     # no caller block — external caller
 * }</pre>
 *
 * <p><b>External connections.</b> A connection with no {@code caller} block
 * has no Itara-managed caller process: it exposes the callee as an inbound
 * entry point. Absence of the whole block is the only way to express this;
 * a {@code caller} block that is present must be complete and valid. An
 * external connection has nowhere to declare failure semantics, because
 * there is no caller-side code for them to apply to.
 *
 * <p><b>Where each plugin kind may be declared</b> (anything else is
 * rejected by {@link #validate()}, never silently ignored):
 *
 * <table>
 *   <caption>Allowed placement per plugin kind</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Plugin kind</th>
 *       <th scope="col">Connection-level</th>
 *       <th scope="col">Caller-side</th>
 *       <th scope="col">Callee-side</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr><th scope="row"><code>transport</code></th><td>yes</td><td>yes</td><td>yes</td></tr>
 *     <tr><th scope="row"><code>serializer</code></th><td>yes</td><td>yes</td><td>yes</td></tr>
 *     <tr><th scope="row"><code>failureSemantics</code></th><td>no</td><td>yes</td><td>no</td></tr>
 *     <tr><th scope="row"><code>authentication</code></th><td>yes</td><td>yes</td><td>yes</td></tr>
 *     <tr><th scope="row"><code>authorization</code></th><td>no</td><td>no</td><td>yes</td></tr>
 *   </tbody>
 * </table>
 *
 * <p><b>Resolution.</b> Each side resolves each plugin kind independently:
 * the side's own block if it declares one, otherwise the connection-level
 * block. A side block replaces the connection-level block of the same kind
 * entirely — block-level replacement, never field-level merging. See
 * {@link #resolveCaller()} and {@link #resolveCallee()}.
 *
 * <p>Unknown fields are silently ignored — forward compatibility for
 * future fields. The one exception is the retired top-level {@code from}
 * and {@code to} fields, which {@link #validate()} rejects with a
 * migration hint rather than letting an old-format config fail confusingly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionEntry {

    /** Required for deserialization. */
    public ConnectionEntry() {}

    /**
     * The character set a connection id is validated against. Kept
     * separate from the node id rule (see {@link Node#VALID_ID}) on
     * purpose: the two happen to be equal today, but this one has its own
     * reason to be conservative, described on {@link #id}.
     */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * This connection's own identifier. Required, and unique across every
     * connection in the wiring config (see WiringConfig.validate()) —
     * unlike the node ids inside the caller/callee blocks, which identify
     * nodes, this identifies the connection itself: the specific link
     * between them, distinct from any other connection that might share
     * the same caller and callee (different transport, different
     * serializer, etc.) or the same callee from a different caller.
     *
     * Case-sensitive. Letters, digits, '.', '_', and '-' only — this set
     * is deliberately conservative: it's exactly Kafka's own topic-name
     * character set, and it stays unencoded-safe in HTTP header values
     * and URL path segments, both of which a transport is free to use to
     * propagate it.
     */
    private String id;

    /**
     * The called side. Mandatory — every connection has a target, and
     * {@code callee.nodeId} is always required.
     */
    private ConnectionSide callee;

    /**
     * The calling side. Optional as a whole: absent means the caller is
     * external to the Itara topology. When present, its nodeId (and
     * everything else it declares) must be valid.
     *
     * A YAML {@code caller:} with no value deserializes to null and is
     * therefore treated the same as an absent block.
     */
    private ConnectionSide caller;

    /**
     * Connection-level transport, shared by both sides unless a side
     * declares its own. Every existing side must end up with a transport,
     * from one place or the other.
     */
    private TransportEntry transport;

    /**
     * Connection-level serializer, shared by both sides unless a side
     * declares its own. Required for every existing side on every
     * connection except direct (colocated) ones — a direct connection
     * never crosses a process boundary, so nothing ever serializes
     * anything on it, and validate() does not demand a serializer for it.
     * For every other connection there is no serializer choice that is
     * safe to assume silently, so a side that resolves none is a
     * configuration error (see validate()).
     */
    private SerializerEntry serializer;

    /**
     * Connection-level authentication, shared by both sides unless a side
     * declares its own. Absent on every level means the noop
     * implementation is used.
     */
    private AuthenticationEntry authentication;

    /**
     * NOT a legal placement. Failure semantics are caller-side only; this
     * field exists solely so that a connection-level declaration is
     * captured and rejected by validate(), instead of being silently
     * dropped as an unknown field.
     */
    private FailureSemanticsEntry failureSemantics;

    /**
     * NOT a legal placement. Authorization is callee-side only; this field
     * exists solely so that a connection-level declaration is captured and
     * rejected by validate(), instead of being silently dropped as an
     * unknown field.
     */
    private AuthorizationEntry authorization;

    /**
     * The retired top-level 'from' field. Captured only so validate() can
     * reject an old-format config with a migration hint; never read for
     * any other purpose.
     */
    @JsonProperty("from")
    private String legacyFrom;

    /**
     * The retired top-level 'to' field. Captured only so validate() can
     * reject an old-format config with a migration hint; never read for
     * any other purpose.
     */
    @JsonProperty("to")
    private String legacyTo;

    // ── Accessors ──────────────────────────────────────────────────────────

    /**
     * Returns this connection's own identifier.
     *
     * @return this connection's own identifier
     */
    public String getId() { return id; }
    /**
     * Sets this connection's own identifier.
     *
     * @param id this connection's own identifier
     */
    public void setId(String id) { this.id = id; }

    /**
     * Returns the callee block.
     *
     * @return the callee block; null only on a config that has not passed
     *         {@link #validate()}
     */
    public ConnectionSide getCallee() { return callee; }
    /**
     * Sets the callee block.
     *
     * @param callee the callee block
     */
    public void setCallee(ConnectionSide callee) { this.callee = callee; }

    /**
     * Returns the caller block.
     *
     * @return the caller block, or null if this is an external connection
     */
    public ConnectionSide getCaller() { return caller; }
    /**
     * Sets the caller block. Null marks the connection as external.
     *
     * @param caller the caller block, or null for an external connection
     */
    public void setCaller(ConnectionSide caller) { this.caller = caller; }

    /**
     * Returns the calling node's id.
     *
     * @return the calling node's id, or null if this is an external connection
     */
    public String getCallerNodeId() { return caller != null ? caller.getNodeId() : null; }

    /**
     * Returns the called node's id.
     *
     * @return the called node's id, or null on a config that has not passed
     *         {@link #validate()}
     */
    public String getCalleeNodeId() { return callee != null ? callee.getNodeId() : null; }

    /**
     * Returns this connection's connection-level transport block, which a
     * side falls back to when it declares none of its own. This is NOT
     * necessarily the transport a side uses — see {@link #resolveCaller()}
     * and {@link #resolveCallee()}.
     *
     * @return the connection-level transport block, or null if not declared
     */
    public TransportEntry getTransport() { return transport; }
    /**
     * Sets the connection-level transport block.
     *
     * @param transport the connection-level transport block
     */
    public void setTransport(TransportEntry transport) { this.transport = transport; }

    /**
     * Returns this connection's connection-level serializer block, which a
     * side falls back to when it declares none of its own. This is NOT
     * necessarily the serializer a side uses — see {@link #resolveCaller()}
     * and {@link #resolveCallee()}.
     *
     * @return the connection-level serializer block, or null if not declared
     */
    public SerializerEntry getSerializer() { return serializer; }
    /**
     * Sets the connection-level serializer block.
     *
     * @param serializer the connection-level serializer block
     */
    public void setSerializer(SerializerEntry serializer) { this.serializer = serializer; }

    /**
     * Returns this connection's connection-level authentication block,
     * which a side falls back to when it declares none of its own. This is
     * NOT necessarily the authentication a side uses — see
     * {@link #resolveCaller()} and {@link #resolveCallee()}.
     *
     * @return the connection-level authentication block, or null if not declared
     */
    public AuthenticationEntry getAuthentication() { return authentication; }
    /**
     * Sets the connection-level authentication block.
     *
     * @param authentication the connection-level authentication block
     */
    public void setAuthentication(AuthenticationEntry authentication) {
        this.authentication = authentication;
    }

    /**
     * Captures a connection-level failureSemantics block. Not a legal
     * placement — failure semantics are caller-side only — so a non-null
     * value here makes {@link #validate()} fail. There is deliberately no
     * getter: nothing may ever read failure semantics from this level.
     *
     * @param failureSemantics the connection-level block, to be rejected
     */
    public void setFailureSemantics(FailureSemanticsEntry failureSemantics) {
        this.failureSemantics = failureSemantics;
    }

    /**
     * Captures a connection-level authorization block. Not a legal
     * placement — authorization is callee-side only — so a non-null value
     * here makes {@link #validate()} fail. There is deliberately no
     * getter: nothing may ever read authorization from this level.
     *
     * @param authorization the connection-level block, to be rejected
     */
    public void setAuthorization(AuthorizationEntry authorization) {
        this.authorization = authorization;
    }

    // ── Derived state ──────────────────────────────────────────────────────

    /**
     * Returns true if the caller is external to the Itara topology, i.e.
     * the connection declares no caller block at all.
     *
     * @return true if the caller is external to the Itara topology
     */
    public boolean isExternal() {
        return caller == null;
    }

    /**
     * Returns true if this is a direct (colocated, in-process) connection.
     *
     * <p>Only meaningful on a validated entry: {@link #validate()}
     * guarantees the caller and callee sides agree on this, so asking the
     * callee side is enough.
     *
     * @return true if this is a direct (colocated, in-process) connection
     */
    public boolean isDirect() {
        return resolveCallee().isDirect();
    }

    // ── Resolution ─────────────────────────────────────────────────────────

    /**
     * Resolves the callee side: for each plugin kind, the callee block's
     * own declaration if it has one, otherwise the connection-level block.
     * The choice is made independently per plugin kind, and it is
     * block-level: a callee-side block, once present, replaces the
     * connection-level block of that kind entirely, so nothing is
     * inherited field by field.
     *
     * <p>Authorization is taken from the callee block only, since it has
     * no connection-level placement.
     *
     * <p>Only call this on a validated entry, which guarantees a callee
     * with a resolvable transport.
     *
     * @return the resolved callee side
     */
    public ResolvedCallee resolveCallee() {
        return new ResolvedCallee(
                callee.getNodeId(),
                pick(callee.getTransport(), transport),
                pick(callee.getSerializer(), serializer),
                pick(callee.getAuthentication(), authentication),
                callee.getAuthorization());
    }

    /**
     * Resolves the caller side: for each plugin kind, the caller block's
     * own declaration if it has one, otherwise the connection-level block.
     * The choice is made independently per plugin kind, and it is
     * block-level: a caller-side block, once present, replaces the
     * connection-level block of that kind entirely, so nothing is
     * inherited field by field.
     *
     * <p>Failure semantics are taken from the caller block only, since
     * they have no connection-level placement.
     *
     * <p>Only call this on a validated entry, which guarantees a caller
     * with a resolvable transport whenever the connection isn't external.
     *
     * @return the resolved caller side
     * @throws IllegalStateException if this is an external connection,
     *         which has no caller to resolve
     */
    public ResolvedCaller resolveCaller() {
        if (caller == null) {
            throw new IllegalStateException(
                    "[Itara] Connection id='" + id + "' is external and has no caller side to resolve.");
        }
        return new ResolvedCaller(
                caller.getNodeId(),
                pick(caller.getTransport(), transport),
                pick(caller.getSerializer(), serializer),
                caller.getFailureSemantics(),
                pick(caller.getAuthentication(), authentication));
    }

    /**
     * The entire resolution rule for one plugin kind: the side's own block
     * wins whole, and the connection-level block is only ever a fallback
     * for when the side declares none. No merging of any kind.
     */
    private static <T> T pick(T sideBlock, T connectionBlock) {
        return sideBlock != null ? sideBlock : connectionBlock;
    }

    // ── Validation ─────────────────────────────────────────────────────────

    /**
     * Validates this connection.
     *
     * <p>Checks run in this order, so the first error reported is the most
     * fundamental one: the connection id; retired top-level fields; the
     * callee block and the node ids of both sides; illegal plugin
     * placements; each declared plugin block on its own; and finally the
     * rules that only make sense once each side is resolved (a transport
     * and serializer on every side, and agreement on direct).
     *
     * @throws ConfigurationException if any required field is missing or
     *         invalid, or a plugin is declared at a placement where it is
     *         not allowed
     */
    public void validate() {
        validateConnectionId();
        rejectRetiredFromTo();
        validateNodeIds();
        rejectIllegalPlacements();
        validatePluginBlocks();
        validateResolvedSides();
    }

    private void validateConnectionId() {
        if (id == null || id.isBlank()) {
            String hint = getCalleeNodeId() != null
                    ? " (callee.nodeId='" + getCalleeNodeId() + "')" : "";
            throw new ConfigurationException(
                    "[Itara] A connection" + hint + " is missing required field 'id'.");
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is invalid — only letters, digits, "
                            + "'.', '_', and '-' are allowed.");
        }
    }

    /**
     * The top-level 'from'/'to' fields are gone. Without this check an
     * old-format config would parse (unknown fields are ignored) and then
     * fail with an unrelated "missing callee" error.
     */
    private void rejectRetiredFromTo() {
        if (legacyFrom != null || legacyTo != null) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' uses the retired top-level 'from'/'to' "
                            + "fields. Declare the target as 'callee: { nodeId: ... }' (mandatory) "
                            + "and the caller as 'caller: { nodeId: ... }'. An external connection "
                            + "is expressed by omitting the 'caller' block entirely.");
        }
    }

    private void validateNodeIds() {
        if (callee == null) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is missing required block 'callee'.");
        }
        validateNodeId("callee", callee.getNodeId());
        if (caller != null) {
            validateNodeId("caller", caller.getNodeId());
        }
    }

    /**
     * A side's nodeId references a node, so it is held to the node id
     * rule itself ({@link Node#VALID_ID}) rather than to a copy of it.
     */
    private void validateNodeId(String side, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            String externalHint = "caller".equals(side)
                    ? " A caller block that is present must be complete; to declare an "
                            + "external connection, omit the 'caller' block entirely."
                    : "";
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is missing required field '" + side
                            + ".nodeId'." + externalHint);
        }
        if (!Node.VALID_ID.matcher(nodeId).matches()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' has an invalid " + side + ".nodeId='"
                            + nodeId + "' — only letters, digits, '.', '_', and '-' are allowed.");
        }
    }

    /**
     * Rejects the four placements the design forbids. Everything else is
     * legal by construction: {@link ResolvedCaller} has no authorization
     * and {@link ResolvedCallee} has no failure semantics, so resolution
     * can only ever read legal placements. The blocks checked here would
     * otherwise be silently dropped.
     */
    private void rejectIllegalPlacements() {
        rejectIfDeclared(failureSemantics, "failureSemantics", "at the connection level", "caller");
        rejectIfDeclared(callee.getFailureSemantics(), "failureSemantics", "in the 'callee' block", "caller");
        rejectIfDeclared(authorization, "authorization", "at the connection level", "callee");
        if (caller != null) {
            rejectIfDeclared(caller.getAuthorization(), "authorization", "in the 'caller' block", "callee");
        }
    }

    private void rejectIfDeclared(Object block, String kind, String where, String validSide) {
        if (block != null) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' declares '" + kind + "' " + where
                            + ", but '" + kind + "' is valid in the '" + validSide + "' block only.");
        }
    }

    /**
     * Validates each declared plugin block on its own — currently, that a
     * declared authentication or authorization block does not have a
     * blank id. The owner text tells the error message where the block was
     * declared.
     */
    private void validatePluginBlocks() {
        String owner = "Connection id='" + id + "'";
        if (authentication != null) {
            authentication.validate(owner + " (connection level)");
        }
        if (callee.getAuthentication() != null) {
            callee.getAuthentication().validate(owner + " (callee)");
        }
        if (callee.getAuthorization() != null) {
            callee.getAuthorization().validate(owner + " (callee)");
        }
        if (caller != null && caller.getAuthentication() != null) {
            caller.getAuthentication().validate(owner + " (caller)");
        }
    }

    /**
     * The rules that depend on what each side ends up with after
     * resolution: every existing side has a transport; both sides agree
     * on 'direct'; a direct connection is never external; and every
     * existing side of a non-direct connection has a serializer.
     */
    private void validateResolvedSides() {
        requireTransport("callee", pick(callee.getTransport(), transport));
        ResolvedCallee resolvedCallee = resolveCallee();

        ResolvedCaller resolvedCaller = null;
        if (caller != null) {
            requireTransport("caller", pick(caller.getTransport(), transport));
            resolvedCaller = resolveCaller();
            if (resolvedCaller.isDirect() != resolvedCallee.isDirect()) {
                throw new ConfigurationException(
                        "[Itara] Connection id='" + id + "' uses the 'direct' transport on only one "
                                + "side (caller resolves to '" + resolvedCaller.getTransport().getId()
                                + "', callee resolves to '" + resolvedCallee.getTransport().getId()
                                + "'). A direct connection is an in-process call, so both sides "
                                + "must resolve to 'direct'.");
            }
        } else if (resolvedCallee.isDirect()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' is direct but has no 'caller' block — direct "
                            + "connections are colocated, in-process calls and cannot be external.");
        }

        if (!resolvedCallee.isDirect()) {
            requireSerializer("callee", resolvedCallee.getSerializer());
            if (resolvedCaller != null) {
                requireSerializer("caller", resolvedCaller.getSerializer());
            }
        }
    }

    private void requireTransport(String side, TransportEntry resolved) {
        if (resolved == null || resolved.getId() == null || resolved.getId().isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' resolves no 'transport.id' for the " + side
                            + " side — declare 'transport' at the connection level or inside the '"
                            + side + "' block.");
        }
    }

    private void requireSerializer(String side, SerializerEntry resolved) {
        if (resolved == null || resolved.getId() == null || resolved.getId().isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection id='" + id + "' resolves no 'serializer.id' for the " + side
                            + " side — declare 'serializer' at the connection level or inside the '"
                            + side + "' block.");
        }
    }

    // ── Node relations ─────────────────────────────────────────────────────

    /**
     * Checks whether this connection touches any of the given node ids.
     *
     * @param nodeIds the node ids to check against
     * @return true if this connection's caller or callee node is among nodeIds
     */
    public boolean isRelatedToAnyOfNodes(List<String> nodeIds) {
        String callerNodeId = getCallerNodeId();
        String calleeNodeId = getCalleeNodeId();

        //if it is a source of a communication
        if (callerNodeId != null && !callerNodeId.isBlank() && nodeIds.contains(callerNodeId)) return true;
        if (calleeNodeId != null && !calleeNodeId.isBlank() && nodeIds.contains(calleeNodeId)) return true;

        return false;
    }

    @Override
    public String toString() {
        return "ConnectionEntry{id='" + id + "', caller=" + caller + ", callee=" + callee
                + ", transport.id='" + (transport != null ? transport.getId() : null)
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', authentication.id='" + (authentication != null ? authentication.getId() : null)
                + "'}";
    }
}
