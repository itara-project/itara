package io.itara.agent;

import io.itara.agent.config.NodeEntry;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.metadata.ItaraMetadataIndex;
import io.itara.agent.metadata.MetadataException;
import io.itara.agent.metadata.MetadataFile;
import io.itara.api.ItaraActivator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Discovers activator classes from component jars.
 *
 * Each component jar includes a file at:
 *   META-INF/itara/activator
 *
 * The file contains a single line: the fully qualified class name of the
 * activator, in the same format as META-INF/itara/transport,
 * META-INF/itara/serializer, etc.
 *
 * Example:
 *   com.example.pricing.PricingActivator
 *
 * The agent scans all jars on the classpath for this file using the
 * classloader's resource enumeration. Multiple component jars can be
 * present in the same JVM (for collocated components). Exactly one
 * activator is expected per component jar.
 *
 * Component identity (id, version, api-version) is no longer read from
 * this file. Instead, once the activator class is loaded, its jar is
 * resolved via the classloader's codesource, mapped to a
 * version-independent artifact name, and looked up in the metadata index
 * built from -Ditara.metadata.dir (see ItaraMetadataIndex). A missing or
 * unresolvable `.itara` entry is a fatal error.
 *
 * Manual process for PoC: the developer creates this file by hand.
 * A build plugin will generate it automatically in a later version.
 */
public class ActivatorScanner {

    private static final Logger log = Logger.getLogger(ActivatorScanner.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/activator";

    /**
     * Scans the classpath for META-INF/itara/activator files.
     *
     * @return Map of component-id -> ActivatedComponent (activator class
     *         plus identity resolved from .itara metadata)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, ActivatedComponent> scan(ClassLoader classLoader, WiringConfig wiringConfig)
            throws IOException, ClassNotFoundException {

        Map<String, ActivatedComponent> result = new HashMap<>();

        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.info("[Itara] Found activator descriptor: " + url);

            String activatorClassName = readActivatorClassName(url);

            Class<?> raw = classLoader.loadClass(activatorClassName);
            if (!ItaraActivator.class.isAssignableFrom(raw)) {
                throw new IllegalStateException(
                        "[Itara] Class " + activatorClassName
                        + " does not implement ItaraActivator. "
                        + "Check META-INF/itara/activator in the component jar.");
            }
            Class<? extends ItaraActivator<?>> activatorClass = (Class<? extends ItaraActivator<?>>) raw;

            MetadataFile metadata = resolveMetadata(activatorClass, url);
            ActivatedComponent component = new ActivatedComponent(activatorClass, metadata);

            result.put(component.getComponentId(), component);
            log.info("[Itara] Registered activator: " + component);
        }

        verify(result, wiringConfig);
        return result;
    }

    /**
     * Reads the single-line activator descriptor: the fully qualified
     * class name of the activator. Blank lines and '#' comments are
     * skipped, matching TransportLoader/SerializerLoader.
     *
     * Visible for testing.
     */
    static String readActivatorClassName(URL url) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                return line;
            }
        }
        throw new IllegalStateException(
                "[Itara] Empty activator descriptor at " + url
                + " — expected a single line containing the activator's "
                + "fully qualified class name.");
    }

    /**
     * Resolves the jar that the given activator class was loaded from,
     * derives its version-independent artifact name, and looks up the
     * corresponding `.itara` metadata file in the metadata index.
     *
     * @throws MetadataException if the jar cannot be resolved (no
     *                            codesource / not a file URL) or if no
     *                            matching `.itara` entry is found.
     */
    private static MetadataFile resolveMetadata(Class<? extends ItaraActivator<?>> activatorClass, URL descriptorUrl) {
        ProtectionDomain protectionDomain = activatorClass.getProtectionDomain();
        CodeSource codeSource = (protectionDomain != null) ? protectionDomain.getCodeSource() : null;
        URL location = (codeSource != null) ? codeSource.getLocation() : null;

        if (location == null) {
            throw new MetadataException(
                    "[Itara] Cannot resolve component identity for activator '"
                    + activatorClass.getName() + "' (descriptor: " + descriptorUrl + "): "
                    + "no codesource available for its jar.");
        }

        String jarFileName = new File(location.getPath()).getName();
        String artifactName = ItaraMetadataIndex.versionIndependentNameFromJar(jarFileName);

        return ItaraMetadataIndex.instance().lookup(artifactName)
                .orElseThrow(() -> new MetadataException(
                        "[Itara] No '" + artifactName + ".itara' file found in "
                        + ItaraMetadataIndex.METADATA_DIR_PROPERTY + " for activator '"
                        + activatorClass.getName() + "' (jar: " + jarFileName + ")."));
    }

    private static void verify(Map<String, ActivatedComponent> activators, WiringConfig wiringConfig) {
        Set<String> components = wiringConfig.getLocalNodes().stream().map(NodeEntry::getComponent).collect(Collectors.toSet());

        boolean hasMissing = false;
        for (String component : components) {
            if (!activators.containsKey(component)) {
                hasMissing = true;
                log.severe("[Itara] FATAL: Activator not found for component " + component + ". Application cannot start.");
            }
        }
        if (hasMissing) {
            throw new IllegalStateException("There are missing activators!");
        }
    }
}
