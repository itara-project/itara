package io.itara.agent.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Note: ItaraMetadataIndex is a process-wide singleton. Each test that
 * calls build() sets up its own @TempDir and only asserts on data it
 * just wrote, so tests don't depend on each other's state — but they
 * are not safe to run in parallel against each other.
 */
@DisplayName("ItaraMetadataIndex")
class ItaraMetadataIndexTest {

    private final ItaraMetadataIndex index = ItaraMetadataIndex.instance();

    @AfterEach
    void clearProperty() {
        System.clearProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY);
    }

    @Nested
    @DisplayName("build + lookup")
    class BuildAndLookup {

        @Test
        @DisplayName("indexes .itara files by filename without extension")
        void indexesByFileNameWithoutExtension(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "inventory-component.itara", """
                    [artifact]
                    kind = "component"
                    id = "inventory"
                    version = "1.0.0"
                    api-version = "1.x"
                    """);
            writeItaraFile(dir, "pricing-service.itara", """
                    [artifact]
                    kind = "component"
                    id = "pricing"
                    version = "2.3.1"
                    api-version = "1.x"
                    """);

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            index.build();

            MetadataFile inventory = index.lookup("inventory-component").orElseThrow();
            assertEquals("inventory", inventory.getArtifact().getId());
            assertEquals("1.0.0", inventory.getArtifact().getVersion());
            assertEquals("1.x", inventory.getArtifact().getApiVersion());

            MetadataFile pricing = index.lookup("pricing-service").orElseThrow();
            assertEquals("pricing", pricing.getArtifact().getId());
            assertEquals("2.3.1", pricing.getArtifact().getVersion());
        }

        @Test
        @DisplayName("lookup returns empty for an unknown artifact name")
        void lookupReturnsEmptyForUnknownArtifact(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "gateway.itara", """
                    [artifact]
                    kind = "component"
                    id = "gateway"
                    version = "0.1.0"
                    api-version = "1.x"
                    """);

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            index.build();

            assertTrue(index.lookup("does-not-exist").isEmpty());
        }

        @Test
        @DisplayName("parses optional [runtime], [itara] and [serializers] sections")
        void parsesOptionalSections(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "inventory-api.itara", """
                    [artifact]
                    kind = "api"
                    id = "inventory-api"
                    version = "1.0.0"
                    api-version = "1.x"

                    [runtime]
                    language = "java"
                    compiler = "21"

                    [itara]
                    spec-version = "0.1"
                    core-version = "0.1+"

                    [serializers]
                    supported = ["json", "protobuf"]
                    """);

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            index.build();

            MetadataFile metadata = index.lookup("inventory-api").orElseThrow();
            assertEquals("java", metadata.getRuntime().getLanguage());
            assertEquals("21", metadata.getRuntime().getCompiler());
            assertEquals("0.1", metadata.getItara().getSpecVersion());
            assertEquals("0.1+", metadata.getItara().getCoreVersion());
            assertEquals(List.of("json", "protobuf"), metadata.getSerializers().getSupported());
        }

        @Test
        @DisplayName("ignores files not ending in .itara")
        void ignoresNonItaraFiles(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "gateway.itara", """
                    [artifact]
                    kind = "component"
                    id = "gateway"
                    version = "0.1.0"
                    api-version = "1.x"
                    """);
            Files.writeString(dir.resolve("README.md"), "not metadata");

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            index.build();

            assertTrue(index.lookup("gateway").isPresent());
            assertTrue(index.lookup("README").isEmpty());
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("throws when itara.metadata.dir is not set")
        void throwsWhenPropertyNotSet() {
            System.clearProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY);
            assertThrows(MetadataException.class, index::build);
        }

        @Test
        @DisplayName("throws when itara.metadata.dir does not exist")
        void throwsWhenDirectoryDoesNotExist(@TempDir Path dir) {
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.resolve("nope").toString());
            assertThrows(MetadataException.class, index::build);
        }

        @Test
        @DisplayName("throws when itara.metadata.dir is a file, not a directory")
        void throwsWhenPathIsNotADirectory(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("not-a-dir");
            Files.writeString(file, "x");

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, file.toString());
            assertThrows(MetadataException.class, index::build);
        }

        @Test
        @DisplayName("throws when a .itara file is missing the [artifact] section")
        void throwsWhenArtifactSectionMissing(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "broken.itara", """
                    [runtime]
                    language = "java"
                    """);

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            assertThrows(MetadataException.class, index::build);
        }

        @Test
        @DisplayName("throws when a .itara file is not valid TOML")
        void throwsWhenFileIsMalformed(@TempDir Path dir) throws IOException {
            writeItaraFile(dir, "broken.itara", """
                    this is not = [valid toml
                    """);

            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, dir.toString());
            assertThrows(MetadataException.class, index::build);
        }
    }

    @Nested
    @DisplayName("versionIndependentNameFromJar")
    class VersionIndependentNameFromJar {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "inventory-component-1.0-SNAPSHOT.jar, inventory-component",
                "pricing-service-2.3.1.jar,            pricing-service",
                "gateway-0.1.0-rc1.jar,                 gateway",
                "gateway.jar,                           gateway",
                "itara-transport-http-0.1.0.jar,        itara-transport-http",
        })
        void derivesVersionIndependentName(String jarFileName, String expected) {
            assertEquals(expected, ItaraMetadataIndex.versionIndependentNameFromJar(jarFileName));
        }
    }

    private static void writeItaraFile(Path dir, String name, String contents) throws IOException {
        Files.writeString(dir.resolve(name), contents);
    }
}
