package dev.itara.agent;

import dev.itara.agent.failuresemantics.NoopFailureSemantics;
import dev.itara.runtime.FailureSemanticsRegistry;
import dev.itara.spi.failuresemantics.ItaraFailureSemanticsFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers failure semantics factories from the classpath.
 *
 * <p>Each failure semantics plugin jar includes a file at:
 *   META-INF/itara/failure-semantics
 *
 * <p>The file contains the fully qualified class name of the
 * {@link ItaraFailureSemanticsFactory} implementation, one per line.
 * Lines starting with # are comments.
 *
 * <p>Example (in itara-failure-semantics-resilience4j.jar):
 *   # Resilience4j failure semantics for Itara
 *   dev.itara.failuresemantics.resilience4j.Resilience4jFailureSemanticsFactory
 *
 * <p>The built-in noop factory is registered directly before classpath
 * scanning — it does not require a descriptor file.
 *
 * <p>The agent calls FailureSemanticsLoader.load() during premain, before
 * any connections are processed.
 */
public class FailureSemanticsLoader {

    /** Not instantiated — all methods are static. */
    private FailureSemanticsLoader() {}

    private static final Logger log = Logger.getLogger(FailureSemanticsLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/failure-semantics";

    /**
     * Registers the built-in noop factory, then scans the classpath for
     * plugin factories and registers those too.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @throws IOException if a descriptor resource cannot be read
     */
    public static void load(ClassLoader classLoader) throws IOException {
        // Always register noop first — it is the default and must always
        // be present regardless of what plugins are on the classpath
        FailureSemanticsRegistry.instance().register(new NoopFailureSemantics.Factory());
        log.fine("[Itara] registered built-in failure semantics type=noop");

        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found failure-semantics descriptor url=" + url);
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
                    if (!ItaraFailureSemanticsFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] skipping failure-semantics factory class=" + line
                                + " reason=does not implement ItaraFailureSemanticsFactory");
                        continue;
                    }
                    ItaraFailureSemanticsFactory factory =
                            (ItaraFailureSemanticsFactory) cls.getDeclaredConstructor().newInstance();
                    FailureSemanticsRegistry.instance().register(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] failure-semantics factory class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate failure-semantics factory class=" + line
                            + " error=" + e.getMessage());
                }
            }
        }
    }
}
