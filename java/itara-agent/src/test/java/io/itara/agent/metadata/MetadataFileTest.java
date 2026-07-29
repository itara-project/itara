package io.itara.agent.metadata;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MetadataFile parsing")
class MetadataFileTest {

    private final TomlMapper mapper = new TomlMapper();

    @Test
    @DisplayName("parses a minimal component .itara file")
    void parsesMinimalComponent() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "inventory"
                version = "1.0.0"
                api-version = "1.x"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getArtifact());
        assertEquals("component", metadata.getArtifact().getKind());
        assertEquals("inventory", metadata.getArtifact().getId());
        assertEquals("1.0.0", metadata.getArtifact().getVersion());
        assertEquals("1.x", metadata.getArtifact().getApiVersion());

        assertNull(metadata.getRuntime());
        assertNull(metadata.getItara());
        assertNull(metadata.getSerializers());
    }

    @Test
    @DisplayName("parses runtime and itara spec sections")
    void parsesRuntimeAndItaraSections() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "inventory"
                version = "1.0.0"
                api-version = "1.x"
                
                [runtime]
                language = "java"
                compiler = "21"
                
                [itara]
                spec-version = "0.1"
                core-version = "0.1+"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertEquals("java", metadata.getRuntime().getLanguage());
        assertEquals("21", metadata.getRuntime().getCompiler());
        assertEquals("0.1", metadata.getItara().getSpecVersion());
        assertEquals("0.1+", metadata.getItara().getCoreVersion());
    }

    @Test
    @DisplayName("parses serializers section for api artifacts")
    void parsesSerializersSection() throws Exception {
        String toml = """
                [artifact]
                kind = "api"
                id = "inventory-api"
                version = "1.0.0"
                api-version = "1.x"
                
                [serializers]
                supported = [
                  { id = "json", version = "1.x" },
                  { id = "protobuf", version = "1.x" },
                ]
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        List<SupportedSerializer> supported = metadata.getSerializers().getSupported();
        assertEquals(2, supported.size());
        assertEquals("json", supported.get(0).getId());
        assertEquals("1.x", supported.get(0).getVersion());
        assertEquals("protobuf", supported.get(1).getId());
        assertEquals("1.x", supported.get(1).getVersion());
    }

    @Test
    @DisplayName("serializers.supported defaults to empty when the section is absent")
    void serializersSupportedDefaultsToEmptyWhenAbsent() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNull(metadata.getSerializers());
    }

    @Test
    @DisplayName("unknown fields in a serializers.supported entry are ignored")
    void unknownFieldsInSupportedSerializerIgnored() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            
            [serializers]
            supported = [
              { id = "json", version = "1.x", future-field = "ignored" },
            ]
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        List<SupportedSerializer> supported = metadata.getSerializers().getSupported();
        assertEquals(1, supported.size());
        assertEquals("json", supported.get(0).getId());
    }

    @Test
    @DisplayName("parses contract message-format for api artifacts")
    void parsesContractMessageFormat() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            
            [contract]
            message-format = "protobuf"
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNotNull(metadata.getContract());
        assertEquals("protobuf", metadata.getContract().getMessageFormat());
        assertTrue(metadata.getContract().hasMessageFormat());
    }

    @Test
    @DisplayName("contract is null when [contract] section is absent")
    void contractNullWhenSectionAbsent() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNull(metadata.getContract());
    }

    @Test
    @DisplayName("message-format defaults to empty string when absent from a declared [contract] section")
    void contractMessageFormatDefaultsToEmpty() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            
            [contract]
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNotNull(metadata.getContract());
        assertEquals("", metadata.getContract().getMessageFormat());
        assertFalse(metadata.getContract().hasMessageFormat());
    }

    @Test
    @DisplayName("explicit empty-string message-format is treated identically to absent")
    void contractMessageFormatExplicitEmptyString() throws Exception {
        String toml = """
            [artifact]
            kind = "api"
            id = "inventory-api"
            version = "1.0.0"
            
            [contract]
            message-format = ""
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertEquals("", metadata.getContract().getMessageFormat());
        assertFalse(metadata.getContract().hasMessageFormat());
    }

    @Test
    @DisplayName("parses serializer type and capabilities for serializer artifacts")
    void parsesSerializerTypeAndCapabilities() throws Exception {
        String toml = """
            [artifact]
            kind = "serializer"
            id = "protobuf"
            version = "0.1.0"
            
            [serializer]
            type = "protobuf"
            
            [serializer.capabilities]
            message-formats = ["protobuf"]
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNotNull(metadata.getSerializer());
        assertEquals("protobuf", metadata.getSerializer().getType());
        assertEquals(List.of("protobuf"), metadata.getSerializer().getCapabilities().getMessageFormats());
    }

    @Test
    @DisplayName("serializer.capabilities.message-formats defaults to empty when capabilities section absent")
    void serializerCapabilitiesDefaultToEmptyWhenAbsent() throws Exception {
        String toml = """
            [artifact]
            kind = "serializer"
            id = "json"
            version = "0.1.0"
            
            [serializer]
            type = "json"
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNotNull(metadata.getSerializer().getCapabilities());
        assertTrue(metadata.getSerializer().getCapabilities().getMessageFormats().isEmpty());
    }

    @Test
    @DisplayName("serializer section is null for non-serializer artifacts")
    void serializerSectionNullForNonSerializerArtifacts() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "inventory"
            version = "1.0.0"
            """;
        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);
        assertNull(metadata.getSerializer());
    }

    @Test
    @DisplayName("unknown fields in serializer and serializer.capabilities sections are ignored")
    void unknownFieldsInSerializerSectionIgnored() throws Exception {
        String toml = """
            [artifact]
            kind = "serializer"
            id = "protobuf"
            version = "0.1.0"
            
            [serializer]
            type = "protobuf"
            future-field = "ignored"
            
            [serializer.capabilities]
            message-formats = ["protobuf"]
            future-capability = "ignored"
            """;
        assertDoesNotThrow(() -> mapper.readValue(toml, MetadataFile.class));
    }

    @Test
    @DisplayName("ignores unknown top-level sections and fields")
    void ignoresUnknownFields() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "inventory"
                version = "1.0.0"
                api-version = "1.x"
                some-future-field = "ignored"
                
                [some-future-section]
                whatever = "ignored"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertEquals("inventory", metadata.getArtifact().getId());
    }

    @Test
    @DisplayName("defaults version and api-version to empty string when absent")
    void defaultsVersionFieldsWhenAbsent() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "inventory"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertEquals("", metadata.getArtifact().getVersion());
        assertEquals("", metadata.getArtifact().getApiVersion());
    }

    @Test
    @DisplayName("artifact is null when [artifact] section is absent")
    void artifactNullWhenSectionAbsent() throws Exception {
        String toml = """
                [runtime]
                language = "java"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNull(metadata.getArtifact());
    }

    @Test
    @DisplayName("parses implemented-event-contracts section with single entry")
    void parsesImplementedEventContractsSingle() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "order-consumer"
            version = "1.0.0"

            [implemented-event-contracts]
            contracts = [
              { id = "order-events/order-placed", version = "1.0.0" }
            ]
            """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getImplementedEventContracts());
        List<ImplementedEventContract> contracts =
                metadata.getImplementedEventContracts().getContracts();
        assertEquals(1, contracts.size());
        assertEquals("order-events/order-placed", contracts.get(0).getId());
        assertEquals("1.0.0", contracts.get(0).getVersion());
    }

    @Test
    @DisplayName("parses implemented-event-contracts section with multiple entries")
    void parsesImplementedEventContractsMultiple() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "notification-service"
            version = "1.0.0"

            [implemented-event-contracts]
            contracts = [
              { id = "order-events/order-placed",    version = "1.0.0" },
              { id = "order-events/order-cancelled", version = "1.0.0" }
            ]
            """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        List<ImplementedEventContract> contracts =
                metadata.getImplementedEventContracts().getContracts();
        assertEquals(2, contracts.size());
        assertEquals("order-events/order-placed", contracts.get(0).getId());
        assertEquals("order-events/order-cancelled", contracts.get(1).getId());
    }

    @Test
    @DisplayName("implementedEventContracts defaults to empty when section absent")
    void implementedEventContractsDefaultsToEmptyWhenAbsent() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "order-producer"
            version = "1.0.0"
            """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getImplementedEventContracts());
        assertTrue(metadata.getImplementedEventContracts().getContracts().isEmpty());
    }

    @Test
    @DisplayName("implementedEventContracts contracts list is empty when declared empty")
    void implementedEventContractsEmptyList() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "order-consumer"
            version = "1.0.0"

            [implemented-event-contracts]
            contracts = []
            """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getImplementedEventContracts());
        assertTrue(metadata.getImplementedEventContracts().getContracts().isEmpty());
    }

    @Test
    @DisplayName("unknown fields in implemented-event-contracts entries are ignored")
    void implementedEventContractsIgnoresUnknownFields() throws Exception {
        String toml = """
            [artifact]
            kind = "component"
            id = "order-consumer"
            version = "1.0.0"

            [implemented-event-contracts]
            contracts = [
              { id = "order-events/order-placed", version = "1.0.0", future-field = "ignored" }
            ]
            """;

        assertDoesNotThrow(() -> mapper.readValue(toml, MetadataFile.class));
    }

    @Test
    @DisplayName("parses transport type and capabilities for a transport artifact")
    void parsesTransportSection() throws Exception {
        String toml = """
                [artifact]
                kind = "transport"
                id = "http"
                version = "0.1.0"
                
                [transport]
                type = "http"
                
                [transport.capabilities]
                native-call-timeout = true
                externally-interruptible = true
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getTransport());
        assertEquals("http", metadata.getTransport().getType());
        assertTrue(metadata.getTransport().getCapabilities().isNativeCallTimeout());
        assertTrue(metadata.getTransport().getCapabilities().isExternallyInterruptible());
    }

    @Test
    @DisplayName("capabilities default to true when [transport.capabilities] section is absent")
    void capabilitiesDefaultToTrueWhenAbsent() throws Exception {
        String toml = """
                [artifact]
                kind = "transport"
                id = "http"
                version = "0.1.0"
                
                [transport]
                type = "http"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNotNull(metadata.getTransport());
        assertNotNull(metadata.getTransport().getCapabilities());
        assertTrue(metadata.getTransport().getCapabilities().isNativeCallTimeout(),
                "native-call-timeout should default to true");
        assertTrue(metadata.getTransport().getCapabilities().isExternallyInterruptible(),
                "externally-interruptible should default to true");
    }

    @Test
    @DisplayName("transport section is null for non-transport artifacts")
    void transportSectionNullForNonTransportArtifacts() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "inventory"
                version = "1.0.0"
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertNull(metadata.getTransport());
    }

    @Test
    @DisplayName("parses native-call-timeout = false correctly")
    void parsesNativeCallTimeoutFalse() throws Exception {
        String toml = """
                [artifact]
                kind = "transport"
                id = "kafka"
                version = "0.1.0"
                
                [transport]
                type = "kafka"
                
                [transport.capabilities]
                native-call-timeout = false
                externally-interruptible = false
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertFalse(metadata.getTransport().getCapabilities().isNativeCallTimeout());
        assertFalse(metadata.getTransport().getCapabilities().isExternallyInterruptible());
    }

    @Test
    @DisplayName("unknown fields in transport section are ignored")
    void unknownFieldsInTransportSectionIgnored() throws Exception {
        String toml = """
                [artifact]
                kind = "transport"
                id = "http"
                version = "0.1.0"
                
                [transport]
                type = "http"
                future-field = "ignored"
                
                [transport.capabilities]
                native-call-timeout = true
                externally-interruptible = true
                future-capability = "ignored"
                """;

        assertDoesNotThrow(() -> mapper.readValue(toml, MetadataFile.class));
    }

    // ── FailureSemanticsCapabilities ──────────────────────────────────────────

    @Test
    void failure_semantics_capabilities_default_to_false_when_absent() throws Exception {
        String toml = """
                [artifact]
                kind = "failure-semantics"
                id = "built-in"
                version = "0.1.0"

                [failure-semantics]
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        FailureSemanticsCapabilities caps = meta.getFailureSemantics().getCapabilities();
        assertFalse(caps.isSupportsExternalTimeout());
    }

    @Test
    void failure_semantics_capabilities_parsed_when_true() throws Exception {
        String toml = """
                [artifact]
                kind = "failure-semantics"
                id = "built-in"
                version = "0.1.0"

                [failure-semantics.capabilities]
                supports-external-timeout = true
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        assertTrue(meta.getFailureSemantics().getCapabilities().isSupportsExternalTimeout());
    }

    @Test
    void failure_semantics_absent_for_non_fs_artifacts() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "calculator"
                version = "1.0.0"
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        assertNull(meta.getFailureSemantics());
    }

    @Test
    void unknown_fields_in_failure_semantics_ignored() throws Exception {
        String toml = """
                [artifact]
                kind = "failure-semantics"
                id = "built-in"
                version = "0.1.0"

                [failure-semantics.capabilities]
                supports-external-timeout = true
                future-field = "ignored"
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        assertTrue(meta.getFailureSemantics().getCapabilities().isSupportsExternalTimeout());
    }

    // ── ApiDependenciesMeta ───────────────────────────────────────────────────

    @Test
    void api_dependencies_absent_is_empty() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "gateway"
                version = "1.0.0"
                api-version = "1.0.0"
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        assertNull(meta.getApiDependencies());
    }

    @Test
    void api_dependencies_single_entry() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "gateway"
                version = "1.0.0"
                api-version = "1.0.0"

                [api-dependencies]
                calls = [
                  { id = "calculator", version = "1.0.0" },
                ]
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        List<ApiDependency> calls = meta.getApiDependencies().getCalls();
        assertEquals(1, calls.size());
        assertEquals("calculator", calls.get(0).getId());
        assertEquals("1.0.0", calls.get(0).getVersion());
    }

    @Test
    void api_dependencies_multiple_entries() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "gateway"
                version = "1.0.0"
                api-version = "1.0.0"

                [api-dependencies]
                calls = [
                  { id = "calculator", version = "1.0.0" },
                  { id = "inventory",  version = "2.1.0" },
                ]
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        List<ApiDependency> calls = meta.getApiDependencies().getCalls();
        assertEquals(2, calls.size());
        assertEquals("calculator", calls.get(0).getId());
        assertEquals("1.0.0", calls.get(0).getVersion());
        assertEquals("inventory", calls.get(1).getId());
        assertEquals("2.1.0", calls.get(1).getVersion());
    }

    @Test
    void api_dependencies_absent_for_non_component_artifacts() throws Exception {
        String toml = """
                [artifact]
                kind = "api"
                id = "calculator"
                version = "1.0.0"
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        assertNull(meta.getApiDependencies());
    }

    @Test
    void unknown_fields_in_api_dependencies_ignored() throws Exception {
        String toml = """
                [artifact]
                kind = "component"
                id = "gateway"
                version = "1.0.0"
                api-version = "1.0.0"

                [api-dependencies]
                calls = [
                  { id = "calculator", version = "1.0.0", future-field = "ignored" },
                ]
                future-section-field = "also ignored"
                """;
        MetadataFile meta = mapper.readValue(toml, MetadataFile.class);
        List<ApiDependency> calls = meta.getApiDependencies().getCalls();
        assertEquals(1, calls.size());
        assertEquals("calculator", calls.get(0).getId());
    }
}
