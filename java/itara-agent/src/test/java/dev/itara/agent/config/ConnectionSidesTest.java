package dev.itara.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the caller/callee structure of a connection declaration: where
 * each plugin kind may be declared, what the {@code caller} and
 * {@code callee} blocks themselves require, and how each side resolves its
 * plugins.
 *
 * <p>Everything here goes through {@link ConfigLoader#parseString(String)},
 * so every entry a test receives has already been validated and its sides
 * can be resolved. The older, structure-independent parsing tests live in
 * ConfigLoaderTest.
 *
 * <p>Rejection tests assert on the message as well as on the exception type:
 * with this many ways for a connection to be invalid, a bare
 * {@code assertThrows} could pass for a reason other than the one under test.
 */
@DisplayName("Connection caller/callee sides")
class ConnectionSidesTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Parses a wiring config and returns all of its connections in
     * declaration order.
     */
    private static List<ConnectionEntry> parseAll(String yaml) {
        return ConfigLoader.parseString(yaml).getConnections();
    }

    /** Parses a wiring config and returns its first connection. */
    private static ConnectionEntry parseOne(String yaml) {
        return parseAll(yaml).get(0);
    }

    /**
     * Asserts that parsing rejects the config with a
     * {@link ConfigurationException} and returns it, so the caller can also
     * pin down why it was rejected.
     */
    private static ConfigurationException rejected(String yaml) {
        return assertThrows(ConfigurationException.class, () -> ConfigLoader.parseString(yaml));
    }

    /** Asserts that the exception message contains every given fragment. */
    private static void assertMessageContains(ConfigurationException e, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(e.getMessage().contains(fragment),
                    "expected the message to contain <" + fragment + "> but it was: " + e.getMessage());
        }
    }

    // ── Legal placements ───────────────────────────────────────────────────

    /**
     * One test per row of the placement table. Where a row allows several
     * placements, each gets its own connection in the same config, and the
     * assertions show which declaration each side actually ended up with.
     */
    @Nested
    @DisplayName("legal placements")
    class LegalPlacements {

        @Test
        @DisplayName("transport: connection level, caller side and callee side")
        void transportAtEveryLegalPlacement() {
            String yaml = """
                    connections:
                      - id: "connection-level"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "caller-side"
                        caller:
                          nodeId: gateway
                          transport:
                            id: caller-http
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "callee-side"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          transport:
                            id: callee-http
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);

            assertAll(
                    () -> assertEquals("http", conns.get(0).resolveCaller().getTransport().getId()),
                    () -> assertEquals("http", conns.get(0).resolveCallee().getTransport().getId()),
                    () -> assertEquals("caller-http", conns.get(1).resolveCaller().getTransport().getId()),
                    () -> assertEquals("http", conns.get(1).resolveCallee().getTransport().getId()),
                    () -> assertEquals("http", conns.get(2).resolveCaller().getTransport().getId()),
                    () -> assertEquals("callee-http", conns.get(2).resolveCallee().getTransport().getId())
            );
        }

        @Test
        @DisplayName("serializer: connection level, caller side and callee side")
        void serializerAtEveryLegalPlacement() {
            String yaml = """
                    connections:
                      - id: "connection-level"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "caller-side"
                        caller:
                          nodeId: gateway
                          serializer:
                            id: caller-json
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "callee-side"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          serializer:
                            id: callee-json
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);

            assertAll(
                    () -> assertEquals("json", conns.get(0).resolveCaller().getSerializer().getId()),
                    () -> assertEquals("json", conns.get(0).resolveCallee().getSerializer().getId()),
                    () -> assertEquals("caller-json", conns.get(1).resolveCaller().getSerializer().getId()),
                    () -> assertEquals("json", conns.get(1).resolveCallee().getSerializer().getId()),
                    () -> assertEquals("json", conns.get(2).resolveCaller().getSerializer().getId()),
                    () -> assertEquals("callee-json", conns.get(2).resolveCallee().getSerializer().getId())
            );
        }

        @Test
        @DisplayName("authentication: connection level, caller side and callee side")
        void authenticationAtEveryLegalPlacement() {
            // No connection-level block in the second and third connection, so
            // the side that declares nothing falls back to noop.
            String yaml = """
                    connections:
                      - id: "connection-level"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                      - id: "caller-side"
                        caller:
                          nodeId: gateway
                          authentication:
                            id: caller-auth
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "callee-side"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          authentication:
                            id: callee-auth
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);

            assertAll(
                    () -> assertEquals("mtls", conns.get(0).resolveCaller().getAuthenticationId()),
                    () -> assertEquals("mtls", conns.get(0).resolveCallee().getAuthenticationId()),
                    () -> assertEquals("caller-auth", conns.get(1).resolveCaller().getAuthenticationId()),
                    () -> assertEquals("noop", conns.get(1).resolveCallee().getAuthenticationId()),
                    () -> assertEquals("noop", conns.get(2).resolveCaller().getAuthenticationId()),
                    () -> assertEquals("callee-auth", conns.get(2).resolveCallee().getAuthenticationId())
            );
        }

        @Test
        @DisplayName("failureSemantics: caller side only")
        void failureSemanticsInCallerBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          failureSemantics:
                            id: built-in
                            maxRetry: 3
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertEquals("built-in", conn.resolveCaller().getFailureSemanticsId());
            assertEquals(4, conn.resolveCaller().getFailureSemanticsConfig().getMaxAttempts());
        }

        @Test
        @DisplayName("authorization: callee side only")
        void authorizationInCalleeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          authorization:
                            id: rule-table
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertEquals("rule-table", conn.resolveCallee().getAuthorizationId());
        }

        @Test
        @DisplayName("the design document's example parses and resolves as documented")
        void designDocumentExampleParses() {
            // Verbatim from the design document: the first connection uses
            // different transports on each side, the second is external.
            String yaml = """
                    connections:
                      - id: "gateway-to-calculator"
                        serializer:
                          id: json
                        callee:
                          nodeId: "calculatorNode"
                          transport:
                            id: hardened-http
                            params:
                              host: "${CALC_HOST:-localhost}"
                              port: "8081"
                          authorization:
                            id: rule-table
                        caller:
                          nodeId: "gatewayNode"
                          transport:
                            id: http
                            params:
                              host: "${CALC_HOST:-localhost}"
                              port: "8081"
                          failureSemantics:
                            id: built-in
                            maxRetry: 3

                      - id: "external-to-gateway"
                        callee:
                          nodeId: "gatewayNode"
                          transport:
                            id: http
                            params:
                              port: "8082"
                          serializer:
                            id: json
                        # no caller block — external caller
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);
            ConnectionEntry internal = conns.get(0);
            ConnectionEntry external = conns.get(1);

            assertAll(
                    () -> assertFalse(internal.isExternal()),
                    () -> assertEquals("gatewayNode", internal.resolveCaller().getNodeId()),
                    () -> assertEquals("calculatorNode", internal.resolveCallee().getNodeId()),
                    () -> assertEquals("http", internal.resolveCaller().getTransport().getId()),
                    () -> assertEquals("hardened-http", internal.resolveCallee().getTransport().getId()),
                    () -> assertEquals("json", internal.resolveCaller().getSerializer().getId()),
                    () -> assertEquals("json", internal.resolveCallee().getSerializer().getId()),
                    () -> assertEquals("built-in", internal.resolveCaller().getFailureSemanticsId()),
                    () -> assertEquals("rule-table", internal.resolveCallee().getAuthorizationId()),

                    () -> assertTrue(external.isExternal()),
                    () -> assertEquals("gatewayNode", external.resolveCallee().getNodeId()),
                    () -> assertEquals("8082", external.resolveCallee().getTransport().getParams().get("port")),
                    () -> assertEquals("json", external.resolveCallee().getSerializer().getId())
            );
        }
    }

    // ── Illegal placements ─────────────────────────────────────────────────

    /**
     * The four cells of the placement table that are not allowed. Each must
     * be rejected outright: a plugin block at one of these placements would
     * otherwise be dropped silently as an unknown field, leaving the
     * operator believing a retry or an access-control rule is in force
     * when it is not.
     */
    @Nested
    @DisplayName("illegal placements")
    class IllegalPlacements {

        @Test
        @DisplayName("failureSemantics at connection level is rejected")
        void failureSemanticsAtConnectionLevel() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        failureSemantics:
                          id: built-in
                    """;
            assertMessageContains(rejected(yaml),
                    "'failureSemantics' at the connection level", "'caller' block only");
        }

        @Test
        @DisplayName("failureSemantics on the callee side is rejected")
        void failureSemanticsInCalleeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          failureSemantics:
                            id: built-in
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml),
                    "'failureSemantics' in the 'callee' block", "'caller' block only");
        }

        @Test
        @DisplayName("authorization at connection level is rejected")
        void authorizationAtConnectionLevel() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authorization:
                          id: rule-table
                    """;
            assertMessageContains(rejected(yaml),
                    "'authorization' at the connection level", "'callee' block only");
        }

        @Test
        @DisplayName("authorization on the caller side is rejected")
        void authorizationInCallerBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          authorization:
                            id: rule-table
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml),
                    "'authorization' in the 'caller' block", "'callee' block only");
        }
    }

    // ── The caller and callee blocks themselves ────────────────────────────

    /**
     * What the {@code caller} and {@code callee} blocks require of
     * themselves, independent of any plugin: which one is mandatory, what a
     * node id must look like, and the retired {@code from}/{@code to}
     * fields.
     */
    @Nested
    @DisplayName("caller and callee blocks")
    class SideBlocks {

        @Test
        @DisplayName("callee block is mandatory")
        void calleeBlockIsMandatory() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "missing required block 'callee'");
        }

        @Test
        @DisplayName("callee.nodeId is mandatory")
        void calleeNodeIdIsMandatory() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          transport:
                            id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "'callee.nodeId'");
        }

        @Test
        @DisplayName("a caller block that is present must have a nodeId")
        void callerBlockRequiresNodeId() {
            // The block is there, so it must be complete. Leaving the caller
            // out entirely is how an external connection is declared.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          transport:
                            id: http
                        callee:
                          nodeId: calculator
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml),
                    "'caller.nodeId'", "omit the 'caller' block entirely");
        }

        @Test
        @DisplayName("callee.nodeId must use the node id character set")
        void calleeNodeIdCharset() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: "not a valid id!"
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "invalid callee.nodeId", "not a valid id!");
        }

        @Test
        @DisplayName("caller.nodeId must use the node id character set")
        void callerNodeIdCharset() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: "not a valid id!"
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "invalid caller.nodeId", "not a valid id!");
        }

        @Test
        @DisplayName("the retired top-level from/to fields are rejected with a migration hint")
        void retiredFromToIsRejected() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        from: gateway
                        to: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "retired top-level 'from'/'to'", "callee");
        }

        @Test
        @DisplayName("the old external form (blank from) is rejected too")
        void retiredExternalFormIsRejected() {
            // In the old format an external connection had a blank 'from'.
            // It is caught through 'to', which every old-format connection has.
            String yaml = """
                    connections:
                      - id: "conn21"
                        from:
                        to: gateway
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "retired top-level 'from'/'to'");
        }

        @Test
        @DisplayName("retired fields are not silently ignored even next to valid caller/callee blocks")
        void retiredFieldsRejectedEvenWithNewBlocks() {
            // A half-migrated config: without the check, 'from' would simply
            // be dropped as an unknown field and the config would look fine.
            String yaml = """
                    connections:
                      - id: "conn21"
                        from: somewhere-else
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "retired top-level 'from'/'to'");
        }

        @Test
        @DisplayName("a caller key with no value is treated as an absent caller block")
        void valueLessCallerIsExternal() {
            // Documented behaviour: YAML gives a bare 'caller:' the value
            // null, which is indistinguishable from the key being absent.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertTrue(parseOne(yaml).isExternal());
        }

        @Test
        @DisplayName("a blank authentication id in the caller block is rejected")
        void blankAuthenticationIdInCallerBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          authentication:
                            id: ""
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "(caller)", "authentication", "blank 'id'");
        }

        @Test
        @DisplayName("a blank authentication id in the callee block is rejected")
        void blankAuthenticationIdInCalleeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          authentication:
                            id: ""
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "(callee)", "authentication", "blank 'id'");
        }
    }

    // ── External connections ───────────────────────────────────────────────

    /**
     * A connection with no {@code caller} block is an external one: there
     * is no Itara-managed caller process, so the connection only has a
     * callee side. It must still validate and resolve like any other, and
     * it must have nowhere to declare caller-only configuration.
     */
    @Nested
    @DisplayName("external connections (no caller block)")
    class ExternalConnections {

        @Test
        @DisplayName("an absent caller block makes the connection external")
        void externalConnectionHasNoCaller() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                        transport:
                          id: http
                          params:
                            port: "8082"
                        serializer:
                          id: json
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertAll(
                    () -> assertTrue(conn.isExternal()),
                    () -> assertNull(conn.getCaller()),
                    () -> assertNull(conn.getCallerNodeId()),
                    () -> assertEquals("gateway", conn.getCalleeNodeId())
            );
        }

        @Test
        @DisplayName("the callee side resolves from the connection level")
        void externalConnectionResolvesCalleeFromConnectionLevel() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                        transport:
                          id: http
                          params:
                            port: "8082"
                        serializer:
                          id: json
                    """;
            ResolvedCallee callee = parseOne(yaml).resolveCallee();

            assertAll(
                    () -> assertEquals("gateway", callee.getNodeId()),
                    () -> assertEquals("http", callee.getTransport().getId()),
                    () -> assertEquals("8082", callee.getTransport().getParams().get("port")),
                    () -> assertEquals("json", callee.getSerializer().getId()),
                    () -> assertFalse(callee.isDirect())
            );
        }

        @Test
        @DisplayName("there is no caller side to resolve")
        void externalConnectionHasNoCallerSideToResolve() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            ConnectionEntry conn = parseOne(yaml);

            IllegalStateException e = assertThrows(IllegalStateException.class, conn::resolveCaller);
            assertTrue(e.getMessage().contains("external"), e.getMessage());
        }

        @Test
        @DisplayName("the callee side can still carry authentication and authorization")
        void externalConnectionCarriesCalleeSidePlugins() {
            // An external entry point is exactly where access control matters
            // most, and both belong to the callee.
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                          authentication:
                            id: mtls
                          authorization:
                            id: rule-table
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            ResolvedCallee callee = parseOne(yaml).resolveCallee();

            assertEquals("mtls", callee.getAuthenticationId());
            assertEquals("rule-table", callee.getAuthorizationId());
        }

        @Test
        @DisplayName("failureSemantics has nowhere to be declared")
        void failureSemanticsHasNowhereToLiveOnExternalConnection() {
            // Retry and timeout are the caller's concern and there is no
            // caller block to hold them; the two remaining places
            // are both illegal placements.
            String atConnectionLevel = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                        transport:
                          id: http
                        serializer:
                          id: json
                        failureSemantics:
                          id: built-in
                    """;
            String inCalleeBlock = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                          failureSemantics:
                            id: built-in
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;

            assertMessageContains(rejected(atConnectionLevel),
                    "'failureSemantics' at the connection level", "'caller' block only");
            assertMessageContains(rejected(inCalleeBlock),
                    "'failureSemantics' in the 'callee' block", "'caller' block only");
        }

        @Test
        @DisplayName("filtering keeps the external connection and only its callee node")
        void externalConnectionKeepsOnlyItsCalleeNode() {
            // gatewayNode is local; the second connection does not involve it.
            String yaml = """
                    nodes:
                      - id: "gatewayNode"
                        component: "gateway"
                      - id: "calculatorNode"
                        component: "calculator"
                      - id: "notifierNode"
                        component: "notifier"
                    connections:
                      - id: "external-to-gateway"
                        callee:
                          nodeId: "gatewayNode"
                        transport:
                          id: http
                          params:
                            port: "8082"
                        serializer:
                          id: json
                      - id: "calculator-to-notifier"
                        caller:
                          nodeId: "calculatorNode"
                        callee:
                          nodeId: "notifierNode"
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            WiringConfig full = ConfigLoader.parseString(yaml);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("gatewayNode"));

            assertAll(
                    () -> assertEquals(1, result.getConnections().size()),
                    () -> assertTrue(result.getConnections().get(0).isExternal()),
                    () -> assertEquals("gatewayNode", result.getConnections().get(0).resolveCallee().getNodeId()),
                    () -> assertEquals(1, result.getNodes().size()),
                    () -> assertEquals("gatewayNode", result.getNodes().get(0).getId())
            );
        }
    }

    // ── Direct connections ─────────────────────────────────────────────────

    /**
     * A direct connection is an in-process call between two colocated
     * nodes. Both sides must agree on it: one side going through a real
     * transport while the other calls in-process cannot work.
     */
    @Nested
    @DisplayName("direct connections")
    class DirectConnections {

        @Test
        @DisplayName("direct declared at connection level applies to both sides")
        void directAtConnectionLevel() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: direct
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertAll(
                    () -> assertTrue(conn.isDirect()),
                    () -> assertTrue(conn.resolveCaller().isDirect()),
                    () -> assertTrue(conn.resolveCallee().isDirect()),
                    // Nothing is ever serialized in-process, so none is required.
                    () -> assertNull(conn.resolveCaller().getSerializer()),
                    () -> assertNull(conn.resolveCallee().getSerializer())
            );
        }

        @Test
        @DisplayName("direct declared on both sides, with no connection-level transport")
        void directDeclaredPerSide() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            id: direct
                        callee:
                          nodeId: calculator
                          transport:
                            id: direct
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertAll(
                    () -> assertTrue(conn.isDirect()),
                    () -> assertTrue(conn.resolveCaller().isDirect()),
                    () -> assertTrue(conn.resolveCallee().isDirect())
            );
        }

        @Test
        @DisplayName("direct on the caller side only is rejected")
        void directOnCallerSideOnlyIsRejected() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            id: direct
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml),
                    "only one side", "caller resolves to 'direct'", "callee resolves to 'http'");
        }

        @Test
        @DisplayName("direct on the callee side only is rejected")
        void directOnCalleeSideOnlyIsRejected() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          transport:
                            id: direct
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml),
                    "only one side", "caller resolves to 'http'", "callee resolves to 'direct'");
        }

        @Test
        @DisplayName("a direct connection cannot be external")
        void directConnectionCannotBeExternal() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: calculator
                        transport:
                          id: direct
                    """;
            assertMessageContains(rejected(yaml), "direct but has no 'caller' block");
        }
    }

    // ── Sides that resolve too little ──────────────────────────────────────

    /**
     * Every side that exists must end up with a transport, and with a
     * serializer unless the connection is direct. A side resolves those
     * only from its own block or the connection level, never from the
     * other side, so declaring a plugin on one side alone leaves the other
     * side without it.
     */
    @Nested
    @DisplayName("sides that resolve no transport or serializer")
    class MissingPieces {

        @Test
        @DisplayName("a transport declared only on the callee leaves the caller without one")
        void transportOnlyOnCalleeLeavesCallerWithout() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          transport:
                            id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "'transport.id' for the caller side");
        }

        @Test
        @DisplayName("a transport declared only on the caller leaves the callee without one")
        void transportOnlyOnCallerLeavesCalleeWithout() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            id: http
                        callee:
                          nodeId: calculator
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "'transport.id' for the callee side");
        }

        @Test
        @DisplayName("a side transport without an id replaces a valid connection-level transport")
        void sideTransportWithoutIdIsNotCompletedFromConnectionLevel() {
            // Block-level replacement: the caller's own (incomplete) block
            // wins whole, so the id in the connection-level block is not
            // borrowed to fill the gap.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            handleTimeout: true
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "'transport.id' for the caller side");
        }

        @Test
        @DisplayName("a serializer declared only on the callee leaves the caller without one")
        void serializerOnlyOnCalleeLeavesCallerWithout() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          serializer:
                            id: json
                        transport:
                          id: http
                    """;
            assertMessageContains(rejected(yaml), "'serializer.id' for the caller side");
        }

        @Test
        @DisplayName("a serializer declared only on the caller leaves the callee without one")
        void serializerOnlyOnCallerLeavesCalleeWithout() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          serializer:
                            id: json
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                    """;
            assertMessageContains(rejected(yaml), "'serializer.id' for the callee side");
        }

        @Test
        @DisplayName("a non-direct connection with no serializer anywhere is rejected")
        void noSerializerAnywhereOnNonDirectConnection() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                    """;
            assertMessageContains(rejected(yaml), "'serializer.id'");
        }

        @Test
        @DisplayName("an external connection still needs a serializer on its callee side")
        void externalConnectionStillNeedsCalleeSerializer() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        callee:
                          nodeId: gateway
                        transport:
                          id: http
                    """;
            assertMessageContains(rejected(yaml), "'serializer.id' for the callee side");
        }
    }

    // ── Resolution ─────────────────────────────────────────────────────────

    /**
     * How each side picks its plugins: independently per plugin kind, the
     * side's own block if it has one and the connection-level block
     * otherwise, and always as a whole block, never field by field.
     */
    @Nested
    @DisplayName("per-side resolution")
    class Resolution {

        @Test
        @DisplayName("overriding one plugin kind leaves the other kinds on the connection level")
        void overridingOneKindLeavesOtherKindsOnConnectionLevel() {
            // The caller overrides only its transport. Its serializer and
            // authentication still fall back, and the callee is untouched.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            id: caller-http
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                    """;
            ConnectionEntry conn = parseOne(yaml);
            ResolvedCaller caller = conn.resolveCaller();
            ResolvedCallee callee = conn.resolveCallee();

            assertAll(
                    () -> assertEquals("caller-http", caller.getTransport().getId()),
                    () -> assertEquals("json", caller.getSerializer().getId()),
                    () -> assertEquals("mtls", caller.getAuthenticationId()),

                    () -> assertEquals("http", callee.getTransport().getId()),
                    () -> assertEquals("json", callee.getSerializer().getId()),
                    () -> assertEquals("mtls", callee.getAuthenticationId())
            );
        }

        @Test
        @DisplayName("each side can override a different plugin kind")
        void differentKindsOverriddenOnEachSide() {
            // Not an all-or-nothing switch per side: the caller overrides its
            // serializer and the callee its authentication, and everything
            // else on both sides still comes from the connection level.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          serializer:
                            id: caller-json
                        callee:
                          nodeId: calculator
                          authentication:
                            id: callee-auth
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                    """;
            ConnectionEntry conn = parseOne(yaml);
            ResolvedCaller caller = conn.resolveCaller();
            ResolvedCallee callee = conn.resolveCallee();

            assertAll(
                    () -> assertEquals("http", caller.getTransport().getId()),
                    () -> assertEquals("caller-json", caller.getSerializer().getId()),
                    () -> assertEquals("mtls", caller.getAuthenticationId()),

                    () -> assertEquals("http", callee.getTransport().getId()),
                    () -> assertEquals("json", callee.getSerializer().getId()),
                    () -> assertEquals("callee-auth", callee.getAuthenticationId())
            );
        }

        @Test
        @DisplayName("a side transport replaces the connection-level block: params and handleTimeout are not inherited")
        void sideTransportReplacesWholeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          transport:
                            id: caller-http
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                          handleTimeout: true
                          params:
                            host: localhost
                            port: "8081"
                        serializer:
                          id: json
                    """;
            ConnectionEntry conn = parseOne(yaml);
            TransportEntry callerTransport = conn.resolveCaller().getTransport();
            TransportEntry calleeTransport = conn.resolveCallee().getTransport();

            assertAll(
                    // The caller's own block carries only an id, and nothing else.
                    () -> assertEquals("caller-http", callerTransport.getId()),
                    () -> assertTrue(callerTransport.getParams().isEmpty()),
                    () -> assertFalse(callerTransport.isHandleTimeout()),

                    // The callee declared nothing, so it gets the whole block.
                    () -> assertEquals("http", calleeTransport.getId()),
                    () -> assertEquals("localhost", calleeTransport.getParams().get("host")),
                    () -> assertEquals("8081", calleeTransport.getParams().get("port")),
                    () -> assertTrue(calleeTransport.isHandleTimeout())
            );
        }

        @Test
        @DisplayName("a side serializer replaces the connection-level block: params are not inherited")
        void sideSerializerReplacesWholeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          serializer:
                            id: callee-json
                        transport:
                          id: http
                        serializer:
                          id: json
                          params:
                            pretty: "true"
                    """;
            ConnectionEntry conn = parseOne(yaml);
            SerializerEntry callerSerializer = conn.resolveCaller().getSerializer();
            SerializerEntry calleeSerializer = conn.resolveCallee().getSerializer();

            assertAll(
                    () -> assertEquals("json", callerSerializer.getId()),
                    () -> assertEquals("true", callerSerializer.getParams().get("pretty")),

                    () -> assertEquals("callee-json", calleeSerializer.getId()),
                    () -> assertTrue(calleeSerializer.getParams().isEmpty())
            );
        }

        @Test
        @DisplayName("a side authentication replaces the connection-level block: params are not inherited")
        void sideAuthenticationReplacesWholeBlock() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          authentication:
                            id: caller-auth
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                          params:
                            trustStore: "/etc/itara/truststore.p12"
                    """;
            ConnectionEntry conn = parseOne(yaml);
            ResolvedCaller caller = conn.resolveCaller();
            ResolvedCallee callee = conn.resolveCallee();

            assertAll(
                    () -> assertEquals("caller-auth", caller.getAuthenticationId()),
                    () -> assertTrue(caller.getAuthenticationConfig().getParams().isEmpty()),

                    () -> assertEquals("mtls", callee.getAuthenticationId()),
                    () -> assertEquals("/etc/itara/truststore.p12",
                            callee.getAuthenticationConfig().getParams().get("trustStore"))
            );
        }

        @Test
        @DisplayName("a side serializer with only params does not borrow the connection-level id")
        void sideSerializerWithOnlyParamsDoesNotBorrowId() {
            // A field-level merge would complete this block with the id
            // 'json' from the connection level. Block-level replacement
            // leaves it without an id, which is a configuration error.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          serializer:
                            params:
                              pretty: "true"
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            assertMessageContains(rejected(yaml), "'serializer.id' for the caller side");
        }

        @Test
        @DisplayName("a side authentication with only params does not borrow the connection-level id")
        void sideAuthenticationWithOnlyParamsDoesNotBorrowId() {
            // An authentication block without an id defaults to noop. It must
            // not pick up 'mtls' from the connection-level block.
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                          authentication:
                            params:
                              note: "params only"
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                    """;
            ConnectionEntry conn = parseOne(yaml);

            assertEquals("noop", conn.resolveCaller().getAuthenticationId());
            assertEquals("mtls", conn.resolveCallee().getAuthenticationId());
        }
    }

    // ── Resolved views ─────────────────────────────────────────────────────

    /**
     * The SPI-facing helpers on the resolved sides. The agent builds its
     * plugins from these, so their defaults for an absent block matter as
     * much as their values for a present one.
     */
    @Nested
    @DisplayName("resolved side helpers")
    class ResolvedViews {

        @Test
        @DisplayName("absent failureSemantics resolves to noop with an empty config")
        void absentFailureSemanticsResolvesToNoop() {
            String yaml = """
                    connections:
                      - id: "conn21"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            ResolvedCaller caller = parseOne(yaml).resolveCaller();

            assertAll(
                    () -> assertNull(caller.getFailureSemantics()),
                    () -> assertEquals("noop", caller.getFailureSemanticsId()),
                    () -> assertNull(caller.getFailureSemanticsConfig().getMaxAttempts()),
                    () -> assertNull(caller.getFailureSemanticsConfig().getTimeout()),
                    () -> assertTrue(caller.getFailureSemanticsConfig().getParams().isEmpty())
            );
        }

        @Test
        @DisplayName("authentication config carries the resolved params, and is empty when absent")
        void authenticationConfigCarriesResolvedParams() {
            String yaml = """
                    connections:
                      - id: "with-authentication"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                        authentication:
                          id: mtls
                          params:
                            trustStore: "/etc/itara/truststore.p12"
                      - id: "without-authentication"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);

            assertAll(
                    () -> assertEquals("/etc/itara/truststore.p12",
                            conns.get(0).resolveCaller().getAuthenticationConfig().getParams().get("trustStore")),
                    () -> assertEquals("/etc/itara/truststore.p12",
                            conns.get(0).resolveCallee().getAuthenticationConfig().getParams().get("trustStore")),
                    () -> assertTrue(conns.get(1).resolveCaller().getAuthenticationConfig().getParams().isEmpty()),
                    () -> assertTrue(conns.get(1).resolveCallee().getAuthenticationConfig().getParams().isEmpty())
            );
        }

        @Test
        @DisplayName("authorization config carries the callee's params, and is empty when absent")
        void authorizationConfigCarriesCalleeParams() {
            String yaml = """
                    connections:
                      - id: "with-authorization"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                          authorization:
                            id: rbac
                            params:
                              policyFile: "/etc/itara/policy.yaml"
                        transport:
                          id: http
                        serializer:
                          id: json
                      - id: "without-authorization"
                        caller:
                          nodeId: gateway
                        callee:
                          nodeId: calculator
                        transport:
                          id: http
                        serializer:
                          id: json
                    """;
            List<ConnectionEntry> conns = parseAll(yaml);
            ResolvedCallee with = conns.get(0).resolveCallee();
            ResolvedCallee without = conns.get(1).resolveCallee();

            assertAll(
                    () -> assertEquals("rbac", with.getAuthorizationId()),
                    () -> assertEquals("/etc/itara/policy.yaml", with.getAuthorizationConfig().getParams().get("policyFile")),
                    () -> assertEquals("noop", without.getAuthorizationId()),
                    () -> assertTrue(without.getAuthorizationConfig().getParams().isEmpty())
            );
        }
    }
}