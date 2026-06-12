package io.itara.agent.metadata;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Index of `.itara` metadata files, scanned once at agent startup from
 * the directory pointed to by -Ditara.metadata.dir.
 *
 * `.itara` files are named without a version (design decision in the
 * component-identity-from-.itara issue / ADR 0008), so the index key
 * for a file is simply its filename without the `.itara` extension —
 * e.g. "inventory-component.itara" is keyed as "inventory-component".
 *
 * Jars on the other hand carry a version in their filename
 * (e.g. "inventory-component-1.0-SNAPSHOT.jar"). Callers resolving a
 * jar to its metadata should first derive the version-independent name
 * via {@link #versionIndependentNameFromJar(String)} and then
 * {@link #lookup(String)} that name.
 *
 * The index is a singleton: it must be built exactly once via
 * {@link #build()} early in agent startup (before
 * ActivatorScanner.scan()), and remains available for the lifetime of
 * the JVM for any later steps that need component/topology metadata.
 *
 * A missing/unreadable directory or an unparsable `.itara` file is a
 * fatal error — component identity is required for the agent to start.
 */
public class ItaraMetadataIndex {

    private static final Logger log = Logger.getLogger(ItaraMetadataIndex.class.getName());

    public static final String METADATA_DIR_PROPERTY = "itara.metadata.dir";

    private static final TomlMapper MAPPER = new TomlMapper();

    private static final ItaraMetadataIndex INSTANCE = new ItaraMetadataIndex();

    /**
     * Matches a trailing version segment on a jar's base name, e.g.
     *   inventory-component-1.0-SNAPSHOT  ->  inventory-component
     *   gateway-0.1.0                     ->  gateway
     *   pricing-service-2.3.1-rc1         ->  pricing-service
     *
     * A version segment is taken to start at the first '-' followed by a
     * digit, consuming everything to the end of the string. Good enough
     * for v0.1 — artifact names containing a "-<digit>" segment of their
     * own (e.g. "service-2-pricing") are not handled correctly, but this
     * is not a convention used in this project.
     */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("-\\d.*$");

    private final Map<String, MetadataFile> entries = new HashMap<>();
    private boolean built = false;

    private ItaraMetadataIndex() {
    }

    public static ItaraMetadataIndex instance() {
        return INSTANCE;
    }

    /**
     * Scans -Ditara.metadata.dir for `.itara` files and builds the index.
     * Must be called exactly once, early in agent startup.
     *
     * @throws MetadataException if the property is not set, the directory
     *                            does not exist, or a `.itara` file is
     *                            missing or cannot be parsed.
     */
    public synchronized void build() {
        String dir = System.getProperty(METADATA_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new MetadataException(
                    "[Itara] No metadata directory specified. "
                            + "Start the JVM with -D" + METADATA_DIR_PROPERTY + "=/path/to/.itara/dir");
        }

        File metadataDir = new File(dir);
        if (!metadataDir.exists() || !metadataDir.isDirectory()) {
            throw new MetadataException(
                    "[Itara] " + METADATA_DIR_PROPERTY + " does not exist "
                            + "or is not a directory: " + dir);
        }

        File[] files = metadataDir.listFiles((d, name) -> name.endsWith(".itara"));
        if (files == null) {
            throw new MetadataException("[Itara] Cannot read metadata dir: " + dir);
        }

        Map<String, MetadataFile> scanned = new HashMap<>();
        for (File file : files) {
            MetadataFile parsed;
            try {
                parsed = MAPPER.readValue(file, MetadataFile.class);
            } catch (IOException e) {
                throw new MetadataException(
                        "[Itara] Could not parse metadata file '" + file + "': " + e.getMessage(), e);
            }

            if (parsed == null || parsed.getArtifact() == null) {
                throw new MetadataException(
                        "[Itara] Metadata file '" + file
                                + "' is missing the required [artifact] section.");
            }

            String artifactName = stripExtension(file.getName());
            if (scanned.containsKey(artifactName)) {
                log.warning("[Itara] Warning: duplicate metadata file for artifact '"
                        + artifactName + "' — keeping first, ignoring: " + file);
                continue;
            }

            scanned.put(artifactName, parsed);
            log.info("[Itara] Discovered metadata for '" + artifactName + "': " + parsed.getArtifact());
        }

        entries.clear();
        entries.putAll(scanned);
        built = true;
        log.info("[Itara] Metadata index built: " + entries.size() + " artifact(s) from " + dir);
    }

    /**
     * Looks up the metadata for a version-independent artifact name
     * (e.g. "inventory-component").
     *
     * @throws IllegalStateException if {@link #build()} has not been
     *                                called yet.
     */
    public Optional<MetadataFile> lookup(String artifactName) {
        ensureBuilt();
        return Optional.ofNullable(entries.get(artifactName));
    }

    private void ensureBuilt() {
        if (!built) {
            throw new IllegalStateException(
                    "[Itara] ItaraMetadataIndex.build() has not been called yet.");
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Derives the version-independent artifact name from a jar's file
     * name, for use as a lookup key into this index.
     *
     *   inventory-component-1.0-SNAPSHOT.jar  ->  inventory-component
     *   gateway-0.1.0.jar                     ->  gateway
     *   pricing-service.jar                   ->  pricing-service
     */
    public static String versionIndependentNameFromJar(String jarFileName) {
        String stem = stripExtension(jarFileName);
        return VERSION_SUFFIX.matcher(stem).replaceFirst("");
    }
}
