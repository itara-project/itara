package dev.itara.agent.config;

import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.failuresemantics.FailureSemanticsConfig;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The caller side of a connection after resolution: for each plugin kind,
 * the caller block if the caller declared one, otherwise the
 * connection-level block. Built by {@link ConnectionEntry#resolveCaller()}
 * once the entry has been validated.
 *
 * <p>Resolution happens independently per plugin kind. A caller that
 * overrides only its transport still falls back to the connection-level
 * serializer, and vice versa. Within one kind the replacement is
 * block-level: if the caller declares a transport, nothing from the
 * connection-level transport block carries over.
 *
 * <p>This type has no authorization component, and that is deliberate.
 * Authorization is callee-side only, so nothing on the caller side can
 * ever read one; see {@link ResolvedCallee}. The mirror holds for
 * failure semantics, which exist only here.
 *
 * <p>An external connection has no caller at all, so it has no
 * {@code ResolvedCaller} either.
 *
 * <p>Instances hold references to the parsed entries, not copies. The
 * entries are treated as read-only once the wiring config has been
 * loaded.
 */
public final class ResolvedCaller {

    /** Type id used whenever an optional plugin block is absent. */
    private static final String NOOP = "noop";

    /** Transport id of the in-process, colocated transport. */
    private static final String DIRECT = "direct";

    private final String nodeId;
    private final TransportEntry transport;
    private final SerializerEntry serializer;
    private final FailureSemanticsEntry failureSemantics;
    private final AuthenticationEntry authentication;

    /**
     * Constructs the resolved caller side. Package-private: only
     * {@link ConnectionEntry} builds these, after validation has already
     * guaranteed the required parts are present.
     *
     * @param nodeId           the caller node's id; never null
     * @param transport        the resolved transport; never null
     * @param serializer       the resolved serializer; null only for a
     *                         direct connection, which never serializes
     * @param failureSemantics the caller's failure semantics; null means noop
     * @param authentication   the resolved authentication; null means noop
     */
    ResolvedCaller(String nodeId,
                   TransportEntry transport,
                   SerializerEntry serializer,
                   FailureSemanticsEntry failureSemantics,
                   AuthenticationEntry authentication) {
        this.nodeId = Objects.requireNonNull(nodeId,
                "[Itara] ResolvedCaller requires a non-null nodeId.");
        this.transport = Objects.requireNonNull(transport,
                "[Itara] ResolvedCaller requires a non-null transport for node '" + nodeId + "'.");
        this.serializer = serializer;
        this.failureSemantics = failureSemantics;
        this.authentication = authentication;
    }

    /**
     * Returns the caller node's id.
     *
     * @return the caller node's id; never null
     */
    public String getNodeId() { return nodeId; }

    /**
     * Returns the transport this caller uses.
     *
     * @return the resolved transport block; never null
     */
    public TransportEntry getTransport() { return transport; }

    /**
     * Returns the serializer this caller uses.
     *
     * @return the resolved serializer block, or null for a direct
     *         connection, which never crosses a process boundary
     */
    public SerializerEntry getSerializer() { return serializer; }

    /**
     * Returns the caller's failure semantics block.
     *
     * @return the failure semantics block, or null if none was declared
     */
    public FailureSemanticsEntry getFailureSemantics() { return failureSemantics; }

    /**
     * Returns the authentication block this caller uses.
     *
     * @return the resolved authentication block, or null if none was
     *         declared on either level
     */
    public AuthenticationEntry getAuthentication() { return authentication; }

    /**
     * Returns whether this caller's resolved transport is the direct
     * (colocated, in-process) one.
     *
     * @return true if the resolved transport id is "direct"
     */
    public boolean isDirect() {
        return DIRECT.equalsIgnoreCase(transport.getId());
    }

    /**
     * Returns the failure semantics type id for this caller.
     * Defaults to "noop" if no failureSemantics block is declared.
     *
     * @return the failure semantics type id
     */
    public String getFailureSemanticsId() {
        return failureSemantics != null ? failureSemantics.getId() : NOOP;
    }

    /**
     * Translates the failureSemantics block into the SPI config.
     * Returns an empty config if no block is declared.
     *
     * @return the SPI-facing failure semantics config
     */
    public FailureSemanticsConfig getFailureSemanticsConfig() {
        return failureSemantics != null
                ? failureSemantics.toSpiConfig()
                : FailureSemanticsConfig.builder().build();
    }

    /**
     * Returns the authentication type id for this caller.
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

    @Override
    public String toString() {
        return "ResolvedCaller{nodeId='" + nodeId
                + "', transport.id='" + transport.getId()
                + "', serializer.id='" + (serializer != null ? serializer.getId() : null)
                + "', failureSemantics.id='" + getFailureSemanticsId()
                + "', authentication.id='" + getAuthenticationId()
                + "'}";
    }
}
