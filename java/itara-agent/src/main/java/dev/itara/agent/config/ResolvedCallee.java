package dev.itara.agent.config;

import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authorization.AuthorizationConfig;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The callee side of a connection after resolution: for each plugin kind,
 * the callee block if the callee declared one, otherwise the
 * connection-level block. Built by {@link ConnectionEntry#resolveCallee()}
 * once the entry has been validated. Every connection has a callee,
 * external ones included.
 *
 * <p>Resolution happens independently per plugin kind. A callee that
 * overrides only its transport still falls back to the connection-level
 * serializer, and vice versa. Within one kind the replacement is
 * block-level: if the callee declares a transport, nothing from the
 * connection-level transport block carries over.
 *
 * <p>This type has no failure semantics component, and that is
 * deliberate. Retry and timeout behavior is exclusively the caller's
 * concern, so nothing on the callee side can ever read it; see
 * {@link ResolvedCaller}. The mirror holds for authorization, which
 * exists only here.
 *
 * <p>Instances hold references to the parsed entries, not copies. The
 * entries are treated as read-only once the wiring config has been
 * loaded.
 */
public final class ResolvedCallee {

    /** Type id used whenever an optional plugin block is absent. */
    private static final String NOOP = "noop";

    /** Transport id of the in-process, colocated transport. */
    private static final String DIRECT = "direct";

    private final String nodeId;
    private final TransportEntry transport;
    private final SerializerEntry serializer;
    private final AuthenticationEntry authentication;
    private final AuthorizationEntry authorization;

    /**
     * Constructs the resolved callee side. Package-private: only
     * {@link ConnectionEntry} builds these, after validation has already
     * guaranteed the required parts are present.
     *
     * @param nodeId         the callee node's id; never null
     * @param transport      the resolved transport; never null
     * @param serializer     the resolved serializer; null only for a
     *                       direct connection, which never serializes
     * @param authentication the resolved authentication; null means noop
     * @param authorization  the callee's authorization; null means noop
     */
    ResolvedCallee(String nodeId,
                   TransportEntry transport,
                   SerializerEntry serializer,
                   AuthenticationEntry authentication,
                   AuthorizationEntry authorization) {
        this.nodeId = Objects.requireNonNull(nodeId,
                "[Itara] ResolvedCallee requires a non-null nodeId.");
        this.transport = Objects.requireNonNull(transport,
                "[Itara] ResolvedCallee requires a non-null transport for node '" + nodeId + "'.");
        this.serializer = serializer;
        this.authentication = authentication;
        this.authorization = authorization;
    }

    /**
     * Returns the callee node's id.
     *
     * @return the callee node's id; never null
     */
    public String getNodeId() { return nodeId; }

    /**
     * Returns the transport this callee uses.
     *
     * @return the resolved transport block; never null
     */
    public TransportEntry getTransport() { return transport; }

    /**
     * Returns the serializer this callee uses.
     *
     * @return the resolved serializer block, or null for a direct
     *         connection, which never crosses a process boundary
     */
    public SerializerEntry getSerializer() { return serializer; }

    /**
     * Returns the authentication block this callee uses.
     *
     * @return the resolved authentication block, or null if none was
     *         declared on either level
     */
    public AuthenticationEntry getAuthentication() { return authentication; }

    /**
     * Returns the callee's authorization block.
     *
     * @return the authorization block, or null if none was declared
     */
    public AuthorizationEntry getAuthorization() { return authorization; }

    /**
     * Returns whether this callee's resolved transport is the direct
     * (colocated, in-process) one.
     *
     * @return true if the resolved transport id is "direct"
     */
    public boolean isDirect() {
        return DIRECT.equalsIgnoreCase(transport.getId());
    }

    /**
     * Returns the authentication type id for this callee.
     * Defaults to "noop" if no authentication block is declared.
     *
     * @return the authentication type id
     */
    public String getAuthenticationId() {
        return authentication != null ? authentication.getId() : NOOP;
    }

    /**
     * Builds the raw authentication config from the resolved block's
     * params. An absent block yields an empty params map.
     *
     * @return the SPI-facing authentication config
     */
    public AuthenticationConfig getAuthenticationConfig() {
        Map<String, String> params = authentication != null
                ? authentication.getParams()
                : Collections.<String, String>emptyMap();
        return AuthenticationConfig.builder().params(params).build();
    }

    /**
     * Returns the authorization type id for this callee.
     * Defaults to "noop" if no authorization block is declared.
     *
     * @return the authorization type id
     */
    public String getAuthorizationId() {
        return authorization != null ? authorization.getId() : NOOP;
    }

    /**
     * Builds the raw authorization config from the block's params. An
     * absent block yields an empty params map.
     *
     * @return the SPI-facing authorization config
     */
    public AuthorizationConfig getAuthorizationConfig() {
        Map<String, String> params = authorization != null
                ? authorization.getParams()
                : Collections.<String, String>emptyMap();
        return AuthorizationConfig.builder().params(params).build();
    }

    @Override
    public String toString() {
        return "ResolvedCallee{nodeId='" + nodeId
                + "', transport.id='" + transport.getId()
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', authentication.id='" + getAuthenticationId()
                + "', authorization.id='" + getAuthorizationId()
                + "'}";
    }
}
