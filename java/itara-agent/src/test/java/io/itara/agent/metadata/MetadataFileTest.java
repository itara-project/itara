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
                supported = ["json", "protobuf"]
                """;

        MetadataFile metadata = mapper.readValue(toml, MetadataFile.class);

        assertEquals(List.of("json", "protobuf"), metadata.getSerializers().getSupported());
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
                nativeCallTimeout = true
                externallyInterruptible = true
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
                "nativeCallTimeout should default to true");
        assertTrue(metadata.getTransport().getCapabilities().isExternallyInterruptible(),
                "externallyInterruptible should default to true");
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
    @DisplayName("parses nativeCallTimeout = false correctly")
    void parsesNativeCallTimeoutFalse() throws Exception {
        String toml = """
                [artifact]
                kind = "transport"
                id = "kafka"
                version = "0.1.0"
                
                [transport]
                type = "kafka"
                
                [transport.capabilities]
                nativeCallTimeout = false
                externallyInterruptible = false
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
                nativeCallTimeout = true
                externallyInterruptible = true
                future-capability = "ignored"
                """;

        assertDoesNotThrow(() -> mapper.readValue(toml, MetadataFile.class));
    }
}
