package io.itara.agent.metadata;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
