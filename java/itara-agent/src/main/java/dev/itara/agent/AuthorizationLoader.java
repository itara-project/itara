package dev.itara.agent;

import dev.itara.agent.authorization.NoopAuthorization;
import dev.itara.runtime.AuthorizationRegistry;
import dev.itara.spi.authorization.ItaraAuthorizationFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers authorization implementations from the
 * classpath.
 *
 * <p>Each authorization jar includes a file at:
 *   META-INF/itara/authorization
 *
 * <p>The file contains the fully qualified class name of the
 * ItaraAuthorizationFactory implementation, one per line. Lines starting
 * with # are comments.
 *
 * <p>The built-in noop factory is registered directly before classpath
 * scanning, mirroring FailureSemanticsLoader — it is always available,
 * regardless of what is on the classpath, since it is the default
 * (spec §16.1).
 *
 * <p>The agent calls AuthorizationLoader.load() during premain, before any
 * connections are processed.
 */
public class AuthorizationLoader {

    private static final Logger log = Logger.getLogger(AuthorizationLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/authorization";

    /**
     * Registers the built-in noop factory, then scans the classpath for
     * META-INF/itara/authorization files, instantiates the declared
     * factory classes, and registers them in the AuthorizationRegistry.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @throws IOException if a descriptor resource cannot be read
     */
    public static void load(ClassLoader classLoader) throws IOException {
        // Always register noop first — it is the default and must always
        // be available, regardless of what is on the classpath.
        AuthorizationRegistry.instance().registerFactory(new NoopAuthorization.Factory());
        log.fine("[Itara] registered built-in authorization id=noop");

        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found authorization descriptor url=" + url);
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
                    if (!ItaraAuthorizationFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] skipping authorization factory class=" + line
                                + " reason=does not implement ItaraAuthorizationFactory");
                        continue;
                    }
                    ItaraAuthorizationFactory factory =
                            (ItaraAuthorizationFactory) cls.getDeclaredConstructor().newInstance();
                    AuthorizationRegistry.instance().registerFactory(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] authorization factory class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate authorization factory class="
                            + line + " error=" + e.getMessage());
                }
            }
        }
    }
}
