package io.itara.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigLoader")
class ConfigLoaderTest {

    // ── Env var substitution ───────────────────────────────────────────────

    @Nested
    @DisplayName("substituteEnvVars")
    class EnvVarSubstitution {

        @Test
        @DisplayName("uses default when variable is not set")
        void usesDefaultWhenNotSet() {
            String result = ConfigLoader.substituteEnvVars("${ITARA_TEST_UNSET:-mydefault}");
            assertEquals("mydefault", result);
        }

        @Test
        @DisplayName("leaves placeholder when variable not set and no default")
        void leavesPlaceholderWhenNoDefault() {
            String input = "${ITARA_TEST_UNSET_NO_DEFAULT}";
            String result = ConfigLoader.substituteEnvVars(input);
            assertEquals(input, result);
        }

        @Test
        @DisplayName("substitutes multiple variables in same string")
        void substitutesMultipleVars() {
            String input = "host: ${MISSING_HOST:-localhost} port: ${MISSING_PORT:-8080}";
            String result = ConfigLoader.substituteEnvVars(input);
            assertEquals("host: localhost port: 8080", result);
        }

        @Test
        @DisplayName("leaves plain strings unchanged")
        void leavesPlainStringsUnchanged() {
            String input = "host: localhost\nport: 8081";
            assertEquals(input, ConfigLoader.substituteEnvVars(input));
        }

        @Test
        @DisplayName("handles empty default value")
        void handlesEmptyDefault() {
            String result = ConfigLoader.substituteEnvVars("${MISSING:-}");
            assertEquals("", result);
        }

        @Test
        @DisplayName("handles default with hyphens and underscores")
        void handlesDefaultWithSpecialChars() {
            String result = ConfigLoader.substituteEnvVars("${MISSING:-hello-world_123}");
            assertEquals("hello-world_123", result);
        }
    }

    // ── Node parsing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("nodes")
    class Nodes {

        @Test
        @DisplayName("parses single node")
        void parsesOneNode() {
            String yaml = """
                    nodes:
                      - id: "calculatorNode"
                        component: "calculator"
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(1, config.getNodes().size());
            assertEquals("calculatorNode", config.getNodes().get(0).getId());
            assertEquals("calculator", config.getNodes().get(0).getComponent());
        }

        @Test
        @DisplayName("parses multiple nodes")
        void parsesMultipleComponents() {
            String yaml = """
                    nodes:
                      - id: "gatewayNode"
                        component: "gateway"
                      - id: "calculatorNode"
                        component: "calculator"
                      - id: "notifierNode"
                        component: "notifier"
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(3, config.getNodes().size());
            assertEquals("gatewayNode",    config.getNodes().get(0).getId());
            assertEquals("gateway", config.getNodes().get(0).getComponent());
            assertEquals("calculatorNode", config.getNodes().get(1).getId());
            assertEquals("calculator", config.getNodes().get(1).getComponent());
            assertEquals("notifierNode",   config.getNodes().get(2).getId());
            assertEquals("notifier", config.getNodes().get(2).getComponent());
        }

        @Test
        @DisplayName("returns empty list when nodes section absent")
        void emptyWhenAbsent() {
            WiringConfig config = ConfigLoader.parseString("nodes: []");
            assertTrue(config.getNodes().isEmpty());
        }

        @Test
        @DisplayName("throws when node id is missing")
        void throwsWhenIdMissing() {
            String yaml = """
                    nodes:
                      - {}
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("throws when node component is missing")
        void throwsWhenComponentMissing() {
            String yaml = """
                    nodes:
                      - id: "calculatorNode"
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("parses unquoted node id")
        void parsesUnquotedId() {
            String yaml = """
                    nodes:
                      - id: calculatorNode
                        component: calculator
                    """;
            assertEquals("calculatorNode", ConfigLoader.parseString(yaml).getNodes().get(0).getId());
            assertEquals("calculator", ConfigLoader.parseString(yaml).getNodes().get(0).getComponent());
        }

        @Test
        @DisplayName("unknown fields in node are ignored")
        void unknownFieldsIgnored() {
            String yaml = """
                    nodes:
                      - id: calculatorNode
                        component: calculator
                        unknownFutureField: somevalue
                    """;
            assertDoesNotThrow(() -> ConfigLoader.parseString(yaml));
        }
    }

    // ── Connection parsing ─────────────────────────────────────────────────

    @Nested
    @DisplayName("connections")
    class Connections {

        @Test
        @DisplayName("parses direct connection")
        void parsesDirectConnection() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: direct
                    """;
            ConnectionEntry conn = ConfigLoader.parseString(yaml)
                    .getConnections().get(0);
            assertEquals("gateway",    conn.getFrom());
            assertEquals("calculator", conn.getTo());
            assertEquals("direct",     conn.getType());
            assertTrue(conn.isDirect());
        }

        @Test
        @DisplayName("parses HTTP connection with all fields")
        void parsesHttpConnection() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: 8081
                        serializer: json
                    """;
            ConnectionEntry conn = ConfigLoader.parseString(yaml)
                    .getConnections().get(0);
            assertEquals("gateway",   conn.getFrom());
            assertEquals("calculator",conn.getTo());
            assertEquals("http",      conn.getType());
            assertEquals("localhost", conn.getHost());
            assertEquals(8081,        conn.getPort());
            assertEquals("json",      conn.getSerializer());
            assertTrue(conn.isHttp());
        }

        @Test
        @DisplayName("no host/port required for direct connection")
        void noHostPortRequiredForDirect() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: direct
                    """;
            assertDoesNotThrow(() -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("unknown fields in connection are ignored")
        void unknownFieldsIgnored() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: direct
                        unknownFutureField: somevalue
                    """;
            assertDoesNotThrow(() -> ConfigLoader.parseString(yaml));
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("throws when 'to' is missing")
        void throwsWhenToMissing() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        type: direct
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("throws when 'type' is missing")
        void throwsWhenTypeMissing() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("throws when port is missing for HTTP connection")
        void throwsWhenPortMissingForHttp() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("throws when port is not a number")
        void throwsWhenPortNotANumber() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: "not-a-number"
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }
    }

    // ── Env var substitution in config values ──────────────────────────────

    @Nested
    @DisplayName("env var substitution in config values")
    class EnvVarInConfig {

        @Test
        @DisplayName("substitutes default in host field")
        void substitutesDefaultInHost() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "${CALC_HOST:-myhost}"
                        port: 8081
                    """;
            assertEquals("myhost", ConfigLoader.parseString(yaml)
                    .getConnections().get(0).getHost());
        }

        @Test
        @DisplayName("substitutes default in port field")
        void substitutesDefaultInPort() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: "${CALC_PORT:-9999}"
                    """;
            assertEquals(9999, ConfigLoader.parseString(yaml)
                    .getConnections().get(0).getPort());
        }

        @Test
        @DisplayName("substitutes default in node id")
        void substitutesDefaultInNodeId() {
            String yaml = """
                    nodes:
                      - id: "${NODE_ID:-calculatorNode}"
                        component: "${COMPONENT:-calculator}"
                    """;
            assertEquals("calculatorNode", ConfigLoader.parseString(yaml).getNodes().get(0).getId());
            assertEquals("calculator", ConfigLoader.parseString(yaml).getNodes().get(0).getComponent());
        }
    }

    // ── Malformed YAML and edge cases ──────────────────────────────────────

    @Nested
    @DisplayName("malformed YAML and edge cases")
    class EdgeCases {

        @Test
        @DisplayName("throws ConfigurationException for invalid YAML syntax")
        void throwsForInvalidYaml() {
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString("this: is: not: valid: yaml: :::"));
        }

        @Test
        @DisplayName("handles empty config gracefully")
        void handlesEmptyConfig() {
            WiringConfig config = ConfigLoader.parseString("");
            assertNotNull(config);
            assertTrue(config.getNodes().isEmpty());
            assertTrue(config.getConnections().isEmpty());
        }

        @Test
        @DisplayName("handles config with only comments")
        void handlesOnlyComments() {
            WiringConfig config = ConfigLoader.parseString(
                    "# just a comment\n# another comment");
            assertNotNull(config);
            assertTrue(config.getNodes().isEmpty());
            assertTrue(config.getConnections().isEmpty());
        }

        @Test
        @DisplayName("handles mixed nodes and connections")
        void handlesMixedConfig() {
            String yaml = """
                    nodes:
                      - id: "gatewayNode"
                        component: "gateway"
                      - id: "calculatorNode"
                        component: "calculator"
                    connections:
                      - from: "gatewayNode"
                        to:   "calculatorNode"
                        type: "direct"
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(2, config.getNodes().size());
            assertEquals(1, config.getConnections().size());
        }
    }

    // ── Filtering ──────────────────────────────────────────────────────────

    static final String FULL_CONFIG = """
            nodes:
              - id: "gatewayNode"
                component: "gateway"
              - id: "calculatorNode"
                component: "calculator"
              - id: "notifierNode"
                component: "notifier"
            connections:
              - from: "gatewayNode"
                to: "calculatorNode"
                type: http
                host: "calculator"
                port: 8081
                serializer: "json"
              - from: "gatewayNode"
                to: "notifierNode"
                type: http
                host: "notifier"
                port: 8082
                serializer: "json"
              - from:
                to: "gatewayNode"
                type: http
                port: 8080
                serializer: "json"
            """;

    @Nested
    @DisplayName("relevantPartOf")
    class Filtering {

        @Test
        @DisplayName("single node — local node plus connected nodes are returned, local is tracked separately")
        void singleNodeReturnsOnlyRelevantConnections() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("calculatorNode"));

            // calculatorNode is local; gatewayNode is kept because it appears in the connection
            // (the agent needs its component id to generate the proxy)
            assertEquals(2, result.getNodes().size());
            assertTrue(result.getNodes().stream().anyMatch(n -> n.getId().equals("calculatorNode")));
            assertTrue(result.getNodes().stream().anyMatch(n -> n.getId().equals("gatewayNode")));
            assertEquals(1, result.getConnections().size());
            assertEquals("calculatorNode", result.getConnections().get(0).getTo());
            // only calculatorNode is local
            assertEquals(List.of("calculatorNode"), result.getLocalNodeIds());
        }

        @Test
        @DisplayName("colocation — both nodes and their connection are returned")
        void colocationReturnsBothNodesAndConnection() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(
                    full, List.of("gatewayNode", "calculatorNode"));

            // gatewayNode and calculatorNode are local
            // notifierNode is kept because gateway→notifier connection is included
            // (gatewayNode is local and notifierNode appears in the connection)
            assertEquals(3, result.getNodes().size());
            assertTrue(result.getNodes().stream().anyMatch(n -> n.getId().equals("gatewayNode")));
            assertTrue(result.getNodes().stream().anyMatch(n -> n.getId().equals("calculatorNode")));
            assertTrue(result.getNodes().stream().anyMatch(n -> n.getId().equals("notifierNode")));
            // gateway→calculator + gateway→notifier + inbound to gateway = 3 connections
            assertEquals(3, result.getConnections().size());
            // both gateway and calculator are local
            assertTrue(result.getLocalNodeIds().contains("gatewayNode"));
            assertTrue(result.getLocalNodeIds().contains("calculatorNode"));
            assertFalse(result.getLocalNodeIds().contains("notifierNode"));
        }

        @Test
        @DisplayName("external inbound included — null-from connection to local node is included")
        void externalInboundIncludedForLocalNode() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("gatewayNode"));

            boolean hasInbound = result.getConnections().stream()
                    .anyMatch(ConnectionEntry::isExternal);
            assertTrue(hasInbound, "External inbound connection should be included for gatewayNode");
        }

        @Test
        @DisplayName("external inbound excluded — null-from connection to other node is not included")
        void externalInboundExcludedForOtherNode() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("calculatorNode"));

            boolean hasInbound = result.getConnections().stream()
                    .anyMatch(ConnectionEntry::isExternal);
            assertFalse(hasInbound, "External inbound to gatewayNode should not appear for calculatorNode");
        }

        @Test
        @DisplayName("connection between two unknown nodes is excluded")
        void connectionBetweenUnknownNodesExcluded() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("notifierNode"));

            // notifierNode only has an inbound from gatewayNode
            // the gateway→calculator connection should NOT appear
            assertTrue(result.getConnections().stream()
                    .noneMatch(c -> "calculatorNode".equals(c.getTo())
                            && "gatewayNode".equals(c.getFrom())),
                    "gateway→calculator connection should not appear for notifierNode");
        }

        @Test
        @DisplayName("outbound connection included when only 'from' node is local")
        void outboundConnectionIncludedWhenOnlyFromIsLocal() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("gatewayNode"));

            assertTrue(result.getConnections().stream()
                    .anyMatch(c -> "gatewayNode".equals(c.getFrom())
                            && "calculatorNode".equals(c.getTo())),
                    "Outbound connection from gatewayNode should be included");
        }

        @Test
        @DisplayName("inbound connection included when only 'to' node is local")
        void inboundConnectionIncludedWhenOnlyToIsLocal() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("calculatorNode"));

            assertTrue(result.getConnections().stream()
                    .anyMatch(c -> "gatewayNode".equals(c.getFrom())
                            && "calculatorNode".equals(c.getTo())),
                    "Inbound connection to calculatorNode should be included");
        }

        @Test
        @DisplayName("unknown node id — results in empty nodes and connections, no error")
        void unknownNodeIdReturnsEmpty() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("unknownNode"));

            assertTrue(result.getNodes().isEmpty(),
                    "Unknown node id should produce no nodes");
            assertTrue(result.getConnections().isEmpty(),
                    "Unknown node id should produce no connections");
        }

        @Test
        @DisplayName("whitespace in node ids is stripped correctly")
        void whitespaceInNodeIdsIsStripped() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            // simulate -Ditara.nodes=gatewayNode, calculatorNode with leading space
            List<String> nodeIds = List.of("gatewayNode", " calculatorNode")
                    .stream().map(String::strip).toList();
            WiringConfig result = ConfigLoader.relevantPartOf(full, nodeIds);

            // gatewayNode and calculatorNode are local, notifierNode is kept
            // because gateway→notifier connection is included
            assertEquals(3, result.getNodes().size());
            assertTrue(result.getLocalNodeIds().contains("gatewayNode"));
            assertTrue(result.getLocalNodeIds().contains("calculatorNode"));
        }

        @Test
        @DisplayName("empty connections — nodes declared but no connections parses cleanly")
        void emptyConnectionsParseCleanly() {
            String yaml = """
                    nodes:
                      - id: "gatewayNode"
                        component: "gateway"
                      - id: "calculatorNode"
                        component: "calculator"
                    """;
            WiringConfig full = ConfigLoader.parseString(yaml);
            WiringConfig result = ConfigLoader.relevantPartOf(
                    full, List.of("gatewayNode", "calculatorNode"));

            assertTrue(result.getConnections().isEmpty());
        }

        @Test
        @DisplayName("local node ids are set correctly on the result")
        void localNodeIdsAreSetCorrectly() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("gatewayNode"));

            assertEquals(List.of("gatewayNode"), result.getLocalNodeIds());
            assertTrue(result.getLocalNodes().stream()
                    .allMatch(n -> n.getId().equals("gatewayNode")));
        }

        @Test
        @DisplayName("getComponentOfNodeId returns correct component for local node")
        void getComponentOfNodeIdReturnsCorrectComponent() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(full, List.of("gatewayNode"));

            assertEquals("gateway", result.getComponentOfNodeId("gatewayNode"));
        }

        @Test
        @DisplayName("all three nodes collocated — all nodes and connections returned")
        void allThreeNodesCollocated() {
            WiringConfig full = ConfigLoader.parseString(FULL_CONFIG);
            WiringConfig result = ConfigLoader.relevantPartOf(
                    full, List.of("gatewayNode", "calculatorNode", "notifierNode"));

            assertEquals(3, result.getNodes().size());
            assertEquals(3, result.getConnections().size());
        }
    }
}
