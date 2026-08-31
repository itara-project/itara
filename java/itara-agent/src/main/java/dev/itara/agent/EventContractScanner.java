package dev.itara.agent;

import dev.itara.agent.metadata.ItaraMetadataIndex;
import dev.itara.agent.metadata.MetadataFile;
import dev.itara.agent.metadata.MetadataException;
import dev.itara.api.EventContractInterface;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Discovers event contract interfaces from events artifact jars.
 *
 * <p>Each events artifact jar includes a descriptor file at:
 *   META-INF/itara/event-contract
 *
 * <p>The file contains one fully qualified interface class name per line.
 * Blank lines and '#' comments are ignored.
 *
 * <p>Example:
 * <pre>{@code
 * com.example.events.OrderPlacedContract
 * com.example.events.OrderCancelledContract
 * }</pre>
 *
 * <p>The full contract reference is constructed as:
 * {@code <collection-id>/<contract-id>}
 * where collection-id is the artifact id from the jar's .itara metadata
 * file (kind = "events") and contract-id is the id from the
 * {@code @EventContractInterface} annotation on the interface.
 *
 * <p>This full reference must match the 'contract' field on the virtual
 * node in the wiring config.
 *
 *
 * <p>See spec §13.2.2.
 */
public class EventContractScanner {

    /** Not instantiated — all methods are static. */
    private EventContractScanner() {}

    private static final Logger log =
            Logger.getLogger(EventContractScanner.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/event-contract";

    /**
     * Scans the classpath for META-INF/itara/event-contract descriptor files.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @return Map of full contract reference -> event contract interface class
     *         e.g. "order-events/order-placed" -> OrderPlacedContract.class
     */
    public static Map<String, Class<?>> scan(ClassLoader classLoader) {
        Map<String, Class<?>> result = new HashMap<>();

        try {
            Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                log.fine("[Itara] found event-contract descriptor url=" + url);
                processDescriptor(url, classLoader, result);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[Itara] Failed to scan for event contract descriptors: "
                            + e.getMessage(), e);
        }

        return result;
    }

    private static void processDescriptor(URL url, ClassLoader classLoader,
                                          Map<String, Class<?>> result) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                loadEventContractClass(line, classLoader, result, url);
            }
        } catch (IllegalStateException | MetadataException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[Itara] Failed to read event contract descriptor at "
                            + url + ": " + e.getMessage(), e);
        }
    }

    private static void loadEventContractClass(String className,
                                               ClassLoader classLoader,
                                               Map<String, Class<?>> result,
                                               URL descriptorUrl) {
        Class<?> cls;
        try {
            cls = classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "[Itara] Event contract class '" + className
                            + "' listed in " + descriptorUrl
                            + " could not be loaded. Is the events jar on the classpath?", e);
        }

        EventContractInterface annotation =
                cls.getAnnotation(EventContractInterface.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    "[Itara] Class '" + className
                            + "' listed in " + descriptorUrl
                            + " is not annotated with @EventContractInterface.");
        }

        String collectionId = resolveCollectionId(cls, descriptorUrl);
        String contractRef  = collectionId + "/" + annotation.id();

        result.put(contractRef, cls);
        log.fine("[Itara] registered event-contract id=" + contractRef + " class=" + className);
    }

    /**
     * Resolves the collection id for the given class by looking up its
     * jar in the metadata index — same codesource approach as ActivatorScanner.
     */
    private static String resolveCollectionId(Class<?> cls, URL descriptorUrl) {
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd != null ? pd.getCodeSource() : null;
        URL location   = cs != null ? cs.getLocation() : null;

        if (location == null) {
            throw new MetadataException(
                    "[Itara] Cannot resolve collection id for event contract '"
                            + cls.getName() + "' (descriptor: " + descriptorUrl
                            + "): no codesource available.");
        }

        String jarFileName   = new File(location.getPath()).getName();
        String artifactName  = ItaraMetadataIndex
                .versionIndependentNameFromJar(jarFileName);
        MetadataFile metadata = ItaraMetadataIndex.instance()
                .lookup(artifactName)
                .orElseThrow(() -> new MetadataException(
                        "[Itara] No '" + artifactName + ".itara' file found for "
                                + "event contract '" + cls.getName()
                                + "' (jar: " + jarFileName + "). "
                                + "Is the events artifact .itara file in "
                                + ItaraMetadataIndex.METADATA_DIR_PROPERTY + "?"));

        String kind = metadata.getArtifact().getKind();
        if (!"events".equals(kind)) {
            throw new MetadataException(
                    "[Itara] Artifact '" + artifactName
                            + "' contains an @EventContractInterface but its .itara "
                            + "file declares kind = '" + kind + "' instead of 'events'. "
                            + "Check the .itara metadata file.");
        }

        return metadata.getArtifact().getId();
    }
}
