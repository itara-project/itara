package io.itara.agent;

import io.itara.runtime.SerializerRegistry;
import io.itara.spi.serializer.ItaraSerializerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers serializer implementations from the classpath.
 *
 * Each serializer jar includes a file at:
 *   META-INF/itara/serializer
 *
 * The file contains the fully qualified class name of the ItaraSerializer
 * implementation, one per line. Lines starting with # are comments.
 *
 * Example (in itara-serializer-json.jar):
 *   # JSON serializer for Itara
 *   itara.serializer.json.JsonSerializerFactory
 *
 * The agent calls SerializerLoader.load() during premain, before any
 * connections are processed. Discovered serializers are registered in
 * SerializerRegistry and are available for the rest of startup.
 *
 * Multiple serializer jars can be present on the classpath simultaneously.
 * Each jar provides its own META-INF/itara/serializer descriptor.
 */
public class SerializerLoader {

    private static final Logger log = Logger.getLogger(SerializerLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/serializer";

    /**
     * Scans the classpath for META-INF/itara/serializer files,
     * instantiates the declared serializer classes, and registers them
     * in the SerializerRegistry.
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
