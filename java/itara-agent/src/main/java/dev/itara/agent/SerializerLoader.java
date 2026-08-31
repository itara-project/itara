package dev.itara.agent;

import dev.itara.runtime.SerializerRegistry;
import dev.itara.spi.serializer.ItaraSerializerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers serializer factories from the classpath.
 *
 * <p>Each serializer jar includes a file at:
 *   META-INF/itara/serializer
 *
 * <p>The file contains the fully qualified class name of the
 * {@link ItaraSerializerFactory} implementation, one per line — not the
 * {@code ItaraSerializer} implementation itself; the factory is what gets
 * discovered and instantiated, and it in turn produces serializer
 * instances on demand. Lines starting with # are comments.
 *
 * <p>Example (in itara-serializer-json.jar):
 *   # JSON serializer for Itara
 *   dev.itara.serializer.json.JsonSerializerFactory
 *
 * <p>The agent calls SerializerLoader.load() during premain, before any
 * connections are processed. Discovered factories are registered in
 * SerializerRegistry and are available for the rest of startup.
 *
 * <p>Multiple serializer jars can be present on the classpath simultaneously.
 * Each jar provides its own META-INF/itara/serializer descriptor.
 */
public class SerializerLoader {

    /** Not instantiated — all methods are static. */
    private SerializerLoader() {}

    private static final Logger log = Logger.getLogger(SerializerLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/serializer";

    /**
     * Scans the classpath for META-INF/itara/serializer files,
     * instantiates the declared factory classes, and registers them
     * in the SerializerRegistry.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @throws IOException if a descriptor resource cannot be read
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            log.warning("[Itara] no serializer implementations found.");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found serializer descriptor url=" + url);
            loadFromDescriptor(url, classLoader);
        }
    }

    private static void loadFromDescriptor(URL url, ClassLoader classLoader)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;

                try {
                    Class<?> cls = classLoader.loadClass(line);
                    if (!ItaraSerializerFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] skipping serializer factory class=" + line
                                + " reason=does not implement ItaraSerializerFactory");
                        continue;
                    }
                    ItaraSerializerFactory factory =
                            (ItaraSerializerFactory) cls.getDeclaredConstructor().newInstance();
                    SerializerRegistry.instance().registerFactory(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] serializer factory class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate serializer factory class="
                            + line + " error=" + e.getMessage());
                }
            }
        }
    }
}
