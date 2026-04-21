package io.itara.agent;

import io.itara.runtime.ItaraObserver;
import io.itara.runtime.ObserverRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;

/**
 * Discovers and registers observer implementations from the classpath.
 *
 * Each observer jar includes a file at:
 *   META-INF/itara/observer
 *
 * The file contains the fully qualified class name of the ItaraObserver
 * implementation, one per line. Lines starting with # are comments.
 *
 * Example (in itara-agent.jar):
 *   # Default logging observer
 *   io.itara.agent.observer.LoggingObserver
 *
 * The agent calls ObserverLoader.load() during premain, before any
 * connections are processed. Discovered observers are registered in
 * ObserverRegistry and receive events for the lifetime of the JVM.
 *
 * Multiple observer jars can be present simultaneously — each registers
 * independently, and all receive every event.
 */
public class ObserverLoader {

    private static final String RESOURCE_PATH = "META-INF/itara/observer";

    /**
     * Scans the classpath for META-INF/itara/observer files,
     * instantiates the declared observer classes, and registers them
     * in the ObserverRegistry.
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            System.out.println("[Itara] WARNING: No observer implementations found. "
                    + "Events will not be recorded.");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            System.out.println("[Itara] Found observer descriptor: " + url);
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
                    if (!ItaraObserver.class.isAssignableFrom(cls)) {
                        System.err.println("[Itara] WARNING: " + line
                                + " does not implement ItaraObserver — skipping.");
                        continue;
                    }
                    ItaraObserver observer =
                            (ItaraObserver) cls.getDeclaredConstructor().newInstance();
                    ObserverRegistry.instance().register(observer);
                } catch (ClassNotFoundException e) {
                    System.err.println("[Itara] WARNING: Observer class not found: "
                            + line + ". Is the observer jar on the classpath?");
                } catch (Exception e) {
                    System.err.println("[Itara] WARNING: Failed to instantiate observer "
                            + line + ": " + e.getMessage());
                }
            }
        }
    }
}
