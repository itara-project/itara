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

    // ── Component parsing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("components")
    class Components {

        @Test
        @DisplayName("parses single component")
        void parsesOneComponent() {
            String yaml = """
                    components:
                      - id: "calculator"
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(1, config.getComponents().size());
            assertEquals("calculator", config.getComponents().get(0).getId());
        }

        @Test
        @DisplayName("parses multiple components")
        void parsesMultipleComponents() {
            String yaml = """
                    components:
                      - id: "gateway"
                      - id: "calculator"
                      - id: "notifier"
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(3, config.getComponents().size());
            assertEquals("gateway",    config.getComponents().get(0).getId());
            assertEquals("calculator", config.getComponents().get(1).getId());
            assertEquals("notifier",   config.getComponents().get(2).getId());
        }

        @Test
        @DisplayName("returns empty list when components section absent")
        void emptyWhenAbsent() {
            WiringConfig config = ConfigLoader.parseString("connections: []");
            assertTrue(config.getComponents().isEmpty());
        }

        @Test
        @DisplayName("throws when component id is missing")
        void throwsWhenIdMissing() {
            String yaml = """
                    components:
                      - {}
                    """;
            assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.parseString(yaml));
        }

        @Test
        @DisplayName("parses unquoted component id")
        void parsesUnquotedId() {
            String yaml = """
                    components:
                      - id: calculator
                    """;
            assertEquals("calculator",
                    ConfigLoader.parseString(yaml).getComponents().get(0).getId());
        }

        @Test
        @DisplayName("unknown fields in component are ignored")
        void unknownFieldsIgnored() {
            String yaml = """
                    components:
                      - id: calculator
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
        @DisplayName("serializer defaults to json when not specified")
        void serializerDefaultsToJson() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: 8081
                    """;
            assertEquals("json", ConfigLoader.parseString(yaml)
                    .getConnections().get(0).getSerializer());
        }

        @Test
        @DisplayName("serializer can be set to java")
        void serializerCanBeSetToJava() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: 8081
                        serializer: java
                    """;
            assertEquals("java", ConfigLoader.parseString(yaml)
                    .getConnections().get(0).getSerializer());
        }

        @Test
        @DisplayName("parses external entry point — from absent")
        void parsesExternalEntryPointAbsent() {
            String yaml = """
                    connections:
                      - to:   "calculator"
                        type: http
                        host: "localhost"
                        port: 8081
                    """;
            assertTrue(ConfigLoader.parseString(yaml)
                    .getConnections().get(0).isExternal());
        }

        @Test
        @DisplayName("parses external entry point — from is empty string")
        void parsesExternalEntryPointEmpty() {
            String yaml = """
                    connections:
                      - from: ""
                        to:   "calculator"
                        type: http
                        host: "localhost"
                        port: 8081
                    """;
            assertTrue(ConfigLoader.parseString(yaml)
                    .getConnections().get(0).isExternal());
        }

        @Test
        @DisplayName("parses multiple connections")
        void parsesMultipleConnections() {
            String yaml = """
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: direct
                      - from: "gateway"
                        to:   "notifier"
                        type: http
                        host: "localhost"
                        port: 9090
                    """;
            List<ConnectionEntry> conns = ConfigLoader.parseString(yaml).getConnections();
            assertEquals(2, conns.size());
            assertTrue(conns.get(0).isDirect());
            assertTrue(conns.get(1).isHttp());
        }

        @Test
        @DisplayName("does not require host/port for direct connections")
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
        @DisplayName("substitutes default in component id")
        void substitutesDefaultInComponentId() {
            String yaml = """
                    components:
                      - id: "${COMPONENT_ID:-calculator}"
                    """;
            assertEquals("calculator", ConfigLoader.parseString(yaml)
                    .getComponents().get(0).getId());
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
            assertTrue(config.getComponents().isEmpty());
            assertTrue(config.getConnections().isEmpty());
        }

        @Test
        @DisplayName("handles config with only comments")
        void handlesOnlyComments() {
            WiringConfig config = ConfigLoader.parseString(
                    "# just a comment\n# another comment");
            assertNotNull(config);
            assertTrue(config.getComponents().isEmpty());
            assertTrue(config.getConnections().isEmpty());
        }

        @Test
        @DisplayName("handles mixed components and connections")
        void handlesMixedConfig() {
            String yaml = """
                    components:
                      - id: "gateway"
                      - id: "calculator"
                    connections:
                      - from: "gateway"
                        to:   "calculator"
                        type: direct
                    """;
            WiringConfig config = ConfigLoader.parseString(yaml);
            assertEquals(2, config.getComponents().size());
            assertEquals(1, config.getConnections().size());
        }
    }
}
