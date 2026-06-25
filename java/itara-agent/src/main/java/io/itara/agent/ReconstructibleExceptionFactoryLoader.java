package io.itara.agent;

import io.itara.exceptions.ItaraReconstructibleExceptionFactory;
import io.itara.runtime.ReconstructibleExceptionRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers reconstructible exception factories from the classpath.
 *
 * Each API artifact jar that supports checked exception reconstruction includes
 * a descriptor file at:
 *   META-INF/itara/exception-factory
 *
 * The file contains the fully qualified class name of the
 * {@link ItaraReconstructibleExceptionFactory} implementation, one per line.
 * Lines starting with # are comments.
 *
 * Example (in calculator-api.jar):
 *   # Exception factory for the calculator contract
 *   com.example.calculator.CalculatorReconstructibleExceptionFactory
 *
 * Unlike failure semantics, there is no built-in default — absence of a
 * factory for a contract is valid and means reconstruction is not supported
 * for that contract. The proxy falls back to ItaraRemoteException silently.
 *
 * The agent calls ReconstructibleExceptionFactoryLoader.load() during premain,
 * before any connections are processed.
 */
public class ReconstructibleExceptionFactoryLoader {

    private static final Logger log = Logger.getLogger(
            ReconstructibleExceptionFactoryLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/exception-factory";

    /**
     * Scans the classpath for exception factory descriptors and registers
     * all discovered factories into the {@link ReconstructibleExceptionRegistry}.
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.info("[Itara] Found exception factory descriptor: " + url);
            loadFromDescriptor(url, classLoader);
        }
    }

    private static void loadFromDescriptor(URL url, ClassLoader classLoader)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;

                try {
                    Class<?> cls = classLoader.loadClass(line);
                    if (!ItaraReconstructibleExceptionFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] WARNING: " + line
                                + " does not implement ItaraReconstructibleExceptionFactory — skipping.");
                        continue;
                    }
                    ItaraReconstructibleExceptionFactory factory =
                            (ItaraReconstructibleExceptionFactory) cls.getDeclaredConstructor().newInstance();
                    ReconstructibleExceptionRegistry.instance().register(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] WARNING: Exception factory class not found: "
                            + line + ". Is the jar on the classpath?");
                } catch (Exception e) {
                    log.warning("[Itara] WARNING: Failed to instantiate exception factory "
                            + line + ": " + e.getMessage());
                }
            }
        }
    }
}
