package io.itara.agent;

import io.itara.api.ItaraActivator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Discovers activator classes from component jars.
 *
 * Each component jar includes a file at:
 *   META-INF/itara/activator
 *
 * The file contains exactly two lines:
 *   component-id=<the component id>
 *   activator=<fully qualified activator class name>
 *
 * Example:
 *   component-id=pricing-service
 *   activator=com.example.pricing.PricingActivator
 *
 * The agent scans all jars on the classpath for this file using the
 * classloader's resource enumeration. Multiple component jars can be
 * present in the same JVM (for collocated components).
 *
 * Manual process for PoC: the developer creates this file by hand.
 * A build plugin will generate it automatically in a later version.
 */
public class ActivatorScanner {

    private static final String RESOURCE_PATH = "META-INF/itara/activator";

    /**
     * Scans the classpath for META-INF/itara/activator files.
     *
     * @return Map of component-id -> activator class
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Class<? extends ItaraActivator<?>>> scan(ClassLoader classLoader)
            throws IOException, ClassNotFoundException {

        Map<String, Class<? extends ItaraActivator<?>>> result = new HashMap<>();

        Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            System.out.println("[Itara] Found activator descriptor: " + url);

            String componentId = null;
            String activatorClassName = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.strip();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    if (line.startsWith("component-id=")) {
                        componentId = line.substring("component-id=".length()).strip();
                    } else if (line.startsWith("activator=")) {
                        activatorClassName = line.substring("activator=".length()).strip();
                    }
                }
            }

            if (componentId == null || activatorClassName == null) {
                System.err.println("[Itara] WARNING: Malformed activator descriptor at "
                        + url + " — skipping. Expected component-id= and activator= lines.");
                continue;
            }

            Class<?> raw = classLoader.loadClass(activatorClassName);
            if (!ItaraActivator.class.isAssignableFrom(raw)) {
                throw new IllegalStateException(
                        "[Itara] Class " + activatorClassName
                        + " does not implement ItaraActivator. "
                        + "Check META-INF/Itara/activator in the component jar.");
            }

            result.put(componentId, (Class<? extends ItaraActivator<?>>) raw);
            System.out.println("[Itara] Registered activator: "
                    + componentId + " -> " + activatorClassName);
        }

        return result;
    }
}
