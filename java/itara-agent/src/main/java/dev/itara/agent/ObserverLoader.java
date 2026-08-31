package dev.itara.agent;

import dev.itara.runtime.ItaraObserver;
import dev.itara.runtime.ObserverRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers observer implementations from the classpath.
 *
 * <p>Each observer jar includes a file at:
 *   META-INF/itara/observer
 *
 * <p>The file contains the fully qualified class name of the ItaraObserver
 * implementation, one per line. Lines starting with # are comments.
 *
 * <p>Example (in itara-agent.jar):
 *   # Default logging observer
 *   dev.itara.agent.observer.LoggingObserver
 *
 * <p>The agent calls ObserverLoader.load() during premain, before any
 * connections are processed. Discovered observers are registered in
 * ObserverRegistry and receive events for the lifetime of the JVM.
 *
 * <p>Multiple observer jars can be present simultaneously — each registers
 * independently, and all receive every event.
 */
public class ObserverLoader {

    /** Not instantiated — all methods are static. */
    private ObserverLoader() {}

    private static final Logger log = Logger.getLogger(ObserverLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/observer";

    /**
     * Scans the classpath for META-INF/itara/observer files,
     * instantiates the declared observer classes, and registers them
     * in the ObserverRegistry.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @throws IOException if a descriptor resource cannot be read
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            log.warning("[Itara] no observer implementations found — events will not be recorded");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found observer descriptor url=" + url);
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
                        log.warning("[Itara] skipping observer class=" + line
                                + " reason=does not implement ItaraObserver");
                        continue;
                    }
                    ItaraObserver observer =
                            (ItaraObserver) cls.getDeclaredConstructor().newInstance();
                    ObserverRegistry.instance().register(observer);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] observer class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate observer class=" + line
                            + " error=" + e.getMessage());
                }
            }
        }
    }
}
