package topos.agent;

import topos.runtime.TransportRegistry;
import topos.spi.ToposTransport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;

/**
 * Discovers and registers transport implementations from the classpath.
 *
 * Each transport jar includes a file at:
 *   META-INF/topos/transport
 *
 * The file contains the fully qualified class name of the ToposTransport
 * implementation, one per line. Lines starting with # are comments.
 *
 * Example (in topos-transport-http.jar):
 *   # HTTP transport for Topos
 *   topos.transport.http.HttpTransport
 *
 * The agent calls TransportLoader.load() during premain, before any
 * connections are processed. Discovered transports are registered in
 * TransportRegistry and are available for the rest of startup.
 *
 * Multiple transport jars can be present on the classpath simultaneously.
 * Each jar provides its own META-INF/topos/transport descriptor.
 */
public class TransportLoader {

    private static final String RESOURCE_PATH = "META-INF/topos/transport";

    /**
     * Scans the classpath for META-INF/topos/transport files,
     * instantiates the declared transport classes, and registers them
     * in the TransportRegistry.
     */
    public static void load(ClassLoader classLoader) throws IOException {
        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);

        if (!resources.hasMoreElements()) {
            System.out.println("[Topos] WARNING: No transport implementations found "
                    + "on the classpath. Add at least one transport jar "
                    + "(e.g. topos-transport-http.jar) to the classpath.");
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            System.out.println("[Topos] Found transport descriptor: " + url);
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
                    if (!ToposTransport.class.isAssignableFrom(cls)) {
                        System.err.println("[Topos] WARNING: " + line
                                + " does not implement ToposTransport — skipping.");
                        continue;
                    }
                    ToposTransport transport =
                            (ToposTransport) cls.getDeclaredConstructor().newInstance();
                    TransportRegistry.instance().register(transport);
                } catch (ClassNotFoundException e) {
                    System.err.println("[Topos] WARNING: Transport class not found: "
                            + line + ". Is the transport jar on the classpath?");
                } catch (Exception e) {
                    System.err.println("[Topos] WARNING: Failed to instantiate transport "
                            + line + ": " + e.getMessage());
                }
            }
        }
    }
}
