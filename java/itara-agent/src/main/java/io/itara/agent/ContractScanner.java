package io.itara.agent;

import io.itara.api.ComponentInterface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Discovers component contract interfaces from API artifact jars.
 *
 * Each API artifact jar includes a descriptor file at:
 *   META-INF/itara/contract
 *
 * The file contains one fully qualified interface class name per line.
 * Blank lines and '#' comments are ignored.
 *
 * Example:
 *   com.example.inventory.InventoryService
 *   com.example.inventory.InventoryAdminService
 *
 * The agent uses the discovered Class objects to generate proxy types
 * for remote connections. The component id is read from the
 * @ComponentInterface annotation on each interface — it must match
 * the component id used in the wiring config.
 *
 * Descriptor files are hand-authored for the PoC. The itara-processor
 * annotation processor will generate them automatically at build time.
 */
public class ContractScanner {

    private static final Logger log = Logger.getLogger(ContractScanner.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/contract";

    /**
     * Scans the classpath for META-INF/itara/contract descriptor files.
     *
     * @return Map of component-id -> contract interface class
     */
    public static Map<String, Class<?>> scan(ClassLoader classLoader) {
        Map<String, Class<?>> result = new HashMap<>();

        try {
            Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                log.info("[Itara] Found contract descriptor: " + url);
                processDescriptor(url, classLoader, result);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[Itara] Failed to scan for contract descriptors: "
                            + e.getMessage(), e);
        }

        return result;
    }

    private static void processDescriptor(URL url, ClassLoader classLoader,
                                          Map<String, Class<?>> result) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                loadContractClass(line, classLoader, result, url);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[Itara] Failed to read contract descriptor at "
                            + url + ": " + e.getMessage(), e);
        }
    }

    private static void loadContractClass(String className,
                                          ClassLoader classLoader,
                                          Map<String, Class<?>> result,
                                          URL descriptorUrl) {
        Class<?> cls;
        try {
            cls = classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "[Itara] Contract class '" + className
                            + "' listed in " + descriptorUrl
                            + " could not be loaded. Is the API jar on the classpath?", e);
        }

        ComponentInterface annotation = cls.getAnnotation(ComponentInterface.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    "[Itara] Class '" + className
                            + "' listed in " + descriptorUrl
                            + " is not annotated with @ComponentInterface.");
        }

        String id = annotation.id();
        result.put(id, cls);
        log.info("[Itara] Registered contract: " + id + " -> " + className);
    }
}
