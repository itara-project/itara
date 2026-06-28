package io.itara.agent;

import io.itara.runtime.TransportRegistry;
import io.itara.spi.transport.ItaraTransportFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers transport implementations from the classpath.
 *
 * Each transport jar includes a file at:
 *   META-INF/itara/transport
 *
 * The file contains the fully qualified class name of the ItaraTransport
 * implementation, one per line. Lines starting with # are comments.
 *
 * Example (in itara-transport-http.jar):
 *   # HTTP transport for Itara
 *   itara.transport.http.HttpTransport
 *
 * The agent calls TransportLoader.load() during premain, before any
 * connections are processed. Discovered transports are registered in
 * TransportRegistry and are available for the rest of startup.
 *
 * Multiple transport jars can be present on the classpath simultaneously.
 * Each jar provides its own META-INF/itara/transport descriptor.
 */
public class TransportLoader {

    private static final Logger log = Logger.getLogger(TransportLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/transport";

    /**
     * Scans the classpath for META-INF/itara/transport files,
     * instantiates the declared transport classes, and registers them
     * in the TransportRegistry.
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            log.warning("[Itara] no transport implementations found.");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found transport descriptor url=" + url);
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
                    if (!ItaraTransportFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] skipping transport factory class="
                                + line + " reason=does not implement ItaraTransportFactory");
                        continue;
                    }
                    ItaraTransportFactory factory =
                            (ItaraTransportFactory) cls.getDeclaredConstructor().newInstance();
                    TransportRegistry.instance().registerFactory(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] transport factory class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate transport factory class="
                            + line + " error=" + e.getMessage());
                }
            }
        }
    }
}
