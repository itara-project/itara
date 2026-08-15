package io.itara.agent;

import io.itara.agent.config.ComponentNode;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.exceptions.ActivatorCountException;
import io.itara.agent.exceptions.ActivatorIdentityMismatchException;
import io.itara.agent.exceptions.ActivatorNotFoundException;
import io.itara.agent.exceptions.ComponentDirectoryNotFoundException;
import io.itara.agent.exceptions.ComponentsDirectoryMisconfiguredException;
import io.itara.agent.metadata.ItaraMetadataIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActivatorScanner")
class ActivatorScannerTest {

    @Nested
    @DisplayName("readActivatorClassName")
    class ReadActivatorClassName {

        @Test
        @DisplayName("reads a single-line FQCN descriptor")
        void readsSingleLineDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "com.example.pricing.PricingActivator\n");

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("skips leading blank lines and comments")
        void skipsBlankLinesAndComments(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, """

                    # this file declares the activator class
                    com.example.pricing.PricingActivator
                    """);

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("strips surrounding whitespace")
        void stripsWhitespace(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "   com.example.pricing.PricingActivator   \n");

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("throws on an empty descriptor")
        void throwsOnEmptyDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "");

            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.readActivatorClassName(file.toUri().toURL()));
        }

        @Test
        @DisplayName("throws on a descriptor containing only comments")
        void throwsOnCommentsOnlyDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "# nothing but comments\n\n");

            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.readActivatorClassName(file.toUri().toURL()));
        }
    }

    @BeforeEach
    void resetSingletons() {
        ActivatorScanner.instance().reset();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private int fixtureClassCounter = 0;



    /**
     * Builds a real jar at dir/{artifactName}-{version}.jar containing the
     * fixture activator's compiled class and a META-INF/itara/activator
     * descriptor naming it. Also writes the matching .itara metadata file
     * (declaring componentId) into metadataDir, keyed by the same
     * version-independent artifact name ActivatorScanner will derive.
     */
    private Path buildComponentJar(Path dir, Path metadataDir,
                                   String artifactName, String version, String componentId) throws IOException {
        String className = "FixtureActivator" + (++fixtureClassCounter);
        Path compileDir = Files.createTempDirectory("itara-test-fixture");
        Path classFile = compileActivatorClass(compileDir, className);

        Path jarPath = dir.resolve(artifactName + "-" + version + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry(className + ".class"));
            Files.copy(classFile, jos);
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/itara/activator"));
            jos.write(className.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Files.writeString(metadataDir.resolve(artifactName + ".itara"), """
            [artifact]
            kind = "component"
            id = "%s"
            version = "%s"
            api-version = "%s"
            """.formatted(componentId, version, version));

        return jarPath;
    }

    private static Path compileActivatorClass(Path outputDir, String simpleClassName) throws IOException {
        String source = """
            public class %s implements io.itara.api.ItaraActivator {
                @Override
                public Object activate() {
                    return new Object();
                }
            }
            """.formatted(simpleClassName);

        Path sourceFile = outputDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            boolean success = compiler.getTask(null, fileManager, null, null, null, units).call();
            if (!success) {
                throw new IOException("Failed to compile fixture activator " + simpleClassName);
            }
        }
        return outputDir.resolve(simpleClassName + ".class");
    }

    /** A jar with no META-INF/itara/activator entry at all — for the "zero found" case. */
    private Path buildJunkJar(Path dir, String name) throws IOException {
        Path jarPath = dir.resolve(name + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("junk.txt"));
            jos.write("not a component".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }

    private final List<URLClassLoader> testClassLoaders = new java.util.ArrayList<>();

    private URLClassLoader classLoaderOver(Path dir, ClassLoader parent) throws IOException {
        try (var stream = Files.list(dir)) {
            List<URL> urls = stream
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), parent);
            testClassLoaders.add(cl);
            return cl;
        }
    }

    @AfterEach
    void closeTestClassLoaders() throws IOException {
        for (URLClassLoader cl : testClassLoaders) {
            cl.close();
        }
        testClassLoaders.clear();
    }

    private WiringConfig configWithLocalNode(String nodeId, String componentId) {
        WiringConfig config = new WiringConfig();
        ComponentNode node = new ComponentNode();
        node.setId(nodeId);
        node.setComponent(componentId);
        config.setNodes(List.of(node));
        config.setLocalNodeIds(List.of(nodeId));
        return config;
    }

    @Nested
    @DisplayName("scan — isolated mode")
    class ScanIsolated {

        @Test
        @DisplayName("throws when the components-dir path itself is not a directory")
        void throwsWhenComponentsDirMisconfigured(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) {
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");
            String badPath = tempDir.resolve("does-not-exist").toString();

            assertThrows(ComponentsDirectoryMisconfiguredException.class,
                    () -> ActivatorScanner.instance().scan(
                            ClassLoader.getSystemClassLoader(), config, badPath));
        }

        @Test
        @DisplayName("throws when a local component's own directory does not exist")
        void throwsWhenComponentDirectoryMissing(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path componentsDir = Files.createDirectory(tempDir.resolve("components"));
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ComponentDirectoryNotFoundException ex = assertThrows(ComponentDirectoryNotFoundException.class,
                    () -> ActivatorScanner.instance().scan(
                            ClassLoader.getSystemClassLoader(), config, componentsDir.toString()));
            assertEquals("conflict-a", ex.getComponentId());
        }

        @Test
        @DisplayName("throws when zero activators are found in the component's directory")
        void throwsWhenZeroActivatorsFound(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path componentsDir = Files.createDirectory(tempDir.resolve("components"));
            Path componentDir = Files.createDirectory(componentsDir.resolve("conflict-a"));
            buildJunkJar(componentDir, "junk");
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorCountException ex = assertThrows(ActivatorCountException.class,
                    () -> ActivatorScanner.instance().scan(
                            ClassLoader.getSystemClassLoader(), config, componentsDir.toString()));
            assertEquals("conflict-a", ex.getComponentId());
            assertEquals(0, ex.getFoundCount());
        }

        @Test
        @DisplayName("throws when more than one activator is found in the component's directory")
        void throwsWhenMultipleActivatorsFound(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path componentsDir = Files.createDirectory(tempDir.resolve("components"));
            Path componentDir = Files.createDirectory(componentsDir.resolve("conflict-a"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(componentDir, metadataDir, "conflict-a-first", "1.0", "conflict-a");
            buildComponentJar(componentDir, metadataDir, "conflict-a-second", "1.0", "conflict-a-2");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorCountException ex = assertThrows(ActivatorCountException.class,
                    () -> ActivatorScanner.instance().scan(
                            ClassLoader.getSystemClassLoader(), config, componentsDir.toString()));
            assertEquals("conflict-a", ex.getComponentId());
            assertEquals(2, ex.getFoundCount());
        }

        @Test
        @DisplayName("throws when the one activator found belongs to a different component")
        void throwsOnIdentityMismatch(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path componentsDir = Files.createDirectory(tempDir.resolve("components"));
            Path componentDir = Files.createDirectory(componentsDir.resolve("conflict-a"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(componentDir, metadataDir, "mismatched", "1.0", "conflict-b");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorIdentityMismatchException ex = assertThrows(ActivatorIdentityMismatchException.class,
                    () -> ActivatorScanner.instance().scan(
                            ClassLoader.getSystemClassLoader(), config, componentsDir.toString()));
            assertEquals("conflict-a", ex.getExpectedComponentId());
            assertEquals("conflict-b", ex.getActualComponentId());
        }

        @Test
        @DisplayName("succeeds and creates a parent-first classloader for a correctly matching component")
        void succeedsWithCorrectlyMatchingComponent(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path componentsDir = Files.createDirectory(tempDir.resolve("components"));
            Path componentDir = Files.createDirectory(componentsDir.resolve("conflict-a"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(componentDir, metadataDir, "conflict-a-component", "1.0", "conflict-a");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

            ActivatorScanner.instance().scan(systemClassLoader, config, componentsDir.toString());

            assertTrue(ActivatorScanner.instance().isIsolated());
            assertEquals("conflict-a", ActivatorScanner.instance().getActivatedComponent("conflict-a").getComponentId());
            ClassLoader componentClassLoader = ActivatorScanner.instance().getClassLoader("conflict-a");
            assertInstanceOf(URLClassLoader.class, componentClassLoader);
            assertSame(systemClassLoader, componentClassLoader.getParent());
        }
    }

    @Nested
    @DisplayName("scan — shared mode")
    class ScanShared {

        @Test
        @DisplayName("throws when no activator is found for a local component")
        void throwsWhenNoActivatorFound(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path emptyDir = Files.createDirectory(tempDir.resolve("empty"));
            URLClassLoader systemClassLoader = classLoaderOver(emptyDir, ClassLoader.getSystemClassLoader());
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorNotFoundException ex = assertThrows(ActivatorNotFoundException.class,
                    () -> ActivatorScanner.instance().scan(systemClassLoader, config, null));
            assertEquals("conflict-a", ex.getComponentId());
        }

        @Test
        @DisplayName("succeeds and uses the given classloader directly, with isolated=false")
        void succeedsAndUsesGivenClassLoaderDirectly(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("shared"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(dir, metadataDir, "conflict-a-component", "1.0", "conflict-a");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            URLClassLoader systemClassLoader = classLoaderOver(dir, ClassLoader.getSystemClassLoader());
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorScanner.instance().scan(systemClassLoader, config, null);

            assertFalse(ActivatorScanner.instance().isIsolated());
            assertSame(systemClassLoader, ActivatorScanner.instance().getClassLoader("conflict-a"));
        }

        @Test
        @DisplayName("warns when an activator is found for a component that is not a local node")
        void warnsOnUnmatchedActivator(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("shared"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(dir, metadataDir, "conflict-a-component", "1.0", "conflict-a");
            buildComponentJar(dir, metadataDir, "conflict-b-component", "1.0", "conflict-b");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            URLClassLoader systemClassLoader = classLoaderOver(dir, ClassLoader.getSystemClassLoader());
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a"); // conflict-b not wired

            List<LogRecord> warnings = new java.util.ArrayList<>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { warnings.add(record); }
                @Override public void flush() {}
                @Override public void close() {}
            };
            Logger.getLogger(ActivatorScanner.class.getName()).addHandler(capture);
            try {
                ActivatorScanner.instance().scan(systemClassLoader, config, null);
            } finally {
                Logger.getLogger(ActivatorScanner.class.getName()).removeHandler(capture);
            }

            assertTrue(warnings.stream()
                    .anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("conflict-b")));
        }
    }

    @Nested
    @DisplayName("scan lifecycle")
    class ScanLifecycle {

        @Test
        @DisplayName("throws when scan() is called more than once")
        void throwsOnDoubleScan(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("shared"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(dir, metadataDir, "conflict-a-component", "1.0", "conflict-a");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            URLClassLoader systemClassLoader = classLoaderOver(dir, ClassLoader.getSystemClassLoader());
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorScanner.instance().scan(systemClassLoader, config, null);

            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.instance().scan(systemClassLoader, config, null));
        }

        @Test
        @DisplayName("getActivatedComponent throws before scan() has succeeded")
        void getActivatedComponentThrowsBeforeScan() {
            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.instance().getActivatedComponent("conflict-a"));
        }

        @Test
        @DisplayName("getClassLoader throws before scan() has succeeded")
        void getClassLoaderThrowsBeforeScan() {
            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.instance().getClassLoader("conflict-a"));
        }

        @Test
        @DisplayName("reset() allows scan() to be called again")
        void resetAllowsRescan(@TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("shared"));
            Path metadataDir = Files.createDirectory(tempDir.resolve("metadata"));
            buildComponentJar(dir, metadataDir, "conflict-a-component", "1.0", "conflict-a");
            System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
            ItaraMetadataIndex.instance().build();
            URLClassLoader systemClassLoader = classLoaderOver(dir, ClassLoader.getSystemClassLoader());
            WiringConfig config = configWithLocalNode("conflictANode", "conflict-a");

            ActivatorScanner.instance().scan(systemClassLoader, config, null);
            ActivatorScanner.instance().reset();

            assertDoesNotThrow(() -> ActivatorScanner.instance().scan(systemClassLoader, config, null));
        }
    }
}
