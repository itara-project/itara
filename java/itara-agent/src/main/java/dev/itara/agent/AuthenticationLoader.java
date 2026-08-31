package dev.itara.agent;

import dev.itara.agent.authentication.NoopAuthentication;
import dev.itara.runtime.AuthenticationRegistry;
import dev.itara.spi.authentication.ItaraAuthenticationFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and registers authentication implementations from the
 * classpath.
 *
 * <p>Each authentication jar includes a file at:
 *   META-INF/itara/authentication
 *
 * <p>The file contains the fully qualified class name of the
 * ItaraAuthenticationFactory implementation, one per line. Lines starting
 * with # are comments.
 *
 * <p>The built-in noop factory is registered directly before classpath
 * scanning, mirroring FailureSemanticsLoader — it is always available,
 * regardless of what is on the classpath, since it is the default
 * (spec §15.1).
 *
 * <p>The agent calls AuthenticationLoader.load() during premain, before any
 * connections are processed.
 */
public class AuthenticationLoader {

    /** Not instantiated — all methods are static. */
    private AuthenticationLoader() {}

    private static final Logger log = Logger.getLogger(AuthenticationLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/authentication";

    /**
     * Registers the built-in noop factory, then scans the classpath for
     * META-INF/itara/authentication files, instantiates the declared
     * factory classes, and registers them in the AuthenticationRegistry.
     *
     * @param classLoader the classloader to scan for descriptor resources
     * @throws IOException if a descriptor resource cannot be read
     */
    public static void load(ClassLoader classLoader) throws IOException {
        // Always register noop first — it is the default and must always
        // be available, regardless of what is on the classpath.
        AuthenticationRegistry.instance().registerFactory(new NoopAuthentication.Factory());
        log.fine("[Itara] registered built-in authentication id=noop");

        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            log.fine("[Itara] found authentication descriptor url=" + url);
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
                    if (!ItaraAuthenticationFactory.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] skipping authentication factory class=" + line
                                + " reason=does not implement ItaraAuthenticationFactory");
                        continue;
                    }
                    ItaraAuthenticationFactory factory =
                            (ItaraAuthenticationFactory) cls.getDeclaredConstructor().newInstance();
                    AuthenticationRegistry.instance().registerFactory(factory);
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] authentication factory class not found class=" + line);
                } catch (Exception e) {
                    log.warning("[Itara] failed to instantiate authentication factory class="
                            + line + " error=" + e.getMessage());
                }
            }
        }
    }
}
