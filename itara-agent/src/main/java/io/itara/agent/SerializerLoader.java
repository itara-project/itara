package io.itara.agent;


import io.itara.runtime.SerializerRegistry;
import io.itara.runtime.TransportRegistry;
import io.itara.spi.ItaraSerializer;
import io.itara.spi.ItaraTransport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;

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
 *   itara.serializer.json.JsonSerializer
 *
 * The agent calls SerializerLoader.load() during premain, before any
 * connections are processed. Discovered serializers are registered in
 * SerializerRegistry and are available for the rest of startup.
 *
 * Multiple serializer jars can be present on the classpath simultaneously.
 * Each jar provides its own META-INF/itara/serializer descriptor.
 */
public class SerializerLoader {

    private static final String RESOURCE_PATH = "META-INF/itara/serializer";

    /**
     * Scans the classpath for META-INF/itara/serializer files,
     * instantiates the declared serializer classes, and registers them
     * in the SerializerRegistry.
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            System.out.println("[Itara] WARNING: No serializer implementations found "
                    + "on the classpath. Add at least one serializer jar "
                    + "(e.g. itara-serializer-json.jar) to the classpath.");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            System.out.println("[Itara] Found serializer descriptor: " + url);
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
                    if (!ItaraSerializer.class.isAssignableFrom(cls)) {
                        System.err.println("[Itara] WARNING: " + line
                                + " does not implement ItaraSerializer — skipping.");
                        continue;
                    }
                    ItaraSerializer serializer =
                            (ItaraSerializer) cls.getDeclaredConstructor().newInstance();
                    SerializerRegistry.instance().register(serializer);
                } catch (ClassNotFoundException e) {
                    System.err.println("[Itara] WARNING: Serializer class not found: "
                            + line + ". Is the serializer jar on the classpath?");
                } catch (Exception e) {
                    System.err.println("[Itara] WARNING: Failed to instantiate serializer "
                            + line + ": " + e.getMessage());
                }
            }
        }
    }
}
