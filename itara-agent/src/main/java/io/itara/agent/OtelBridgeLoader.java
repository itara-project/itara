package io.itara.agent;

import io.itara.runtime.NoOpOtelBridge;
import io.itara.runtime.OtelBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Discovers and loads the OtelBridge implementation from itara.lib.dir.
 *
 * Looks for META-INF/itara/otel-bridge descriptor in the child-first
 * classloader. If found, instantiates the declared class. If not found,
 * returns NoOpOtelBridge so the system works without OTel.
 *
 * Only one OtelBridge implementation is supported — the first discovered
 * wins. Multiple OTel bridges make no sense since OTel is a singleton
 * (GlobalOpenTelemetry).
 */
public class OtelBridgeLoader {

    private static final Logger log =
            Logger.getLogger(OtelBridgeLoader.class.getName());

    private static final String RESOURCE_PATH = "META-INF/itara/otel-bridge";

    /**
     * Loads the OtelBridge implementation from the given classloader.
     * Returns NoOpOtelBridge if no implementation is found or loading fails.
     */
    public static OtelBridge load(ClassLoader classLoader) {
        Enumeration<URL> resources;
        try {
            resources = classLoader.getResources(RESOURCE_PATH);
        } catch (IOException e) {
            log.warning("[Itara] Failed to scan for OtelBridge descriptor: "
                    + e.getMessage() + ". Using no-op bridge.");
            return new NoOpOtelBridge();
        }

        if (!resources.hasMoreElements()) {
            log.info("[Itara] No OtelBridge implementation found in itara.lib.dir. "
                    + "OTel integration disabled — using Itara context generation. "
                    + "Add itara-observability-otel to itara.lib.dir to enable OTel.");
            return new NoOpOtelBridge();
        }

        URL url = resources.nextElement();
        if (resources.hasMoreElements()) {
            log.warning("[Itara] Multiple OtelBridge descriptors found. "
                    + "Only one is supported — using first: " + url);
        }

        return loadFromDescriptor(url, classLoader);
    }

    private static OtelBridge loadFromDescriptor(URL url, ClassLoader classLoader) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;

                try {
                    Class<?> cls = classLoader.loadClass(line);
                    if (!OtelBridge.class.isAssignableFrom(cls)) {
                        log.warning("[Itara] " + line
                                + " does not implement OtelBridge — "
                                + "using no-op bridge.");
                        return new NoOpOtelBridge();
                    }
                    OtelBridge bridge =
                            (OtelBridge) cls.getDeclaredConstructor().newInstance();
                    log.info("[Itara] Loaded OtelBridge: " + line);
                    return bridge;
                } catch (ClassNotFoundException e) {
                    log.warning("[Itara] OtelBridge class not found: " + line
                            + ". Is itara-observability-otel in itara.lib.dir? "
                            + "Using no-op bridge.");
                } catch (Exception e) {
                    log.warning("[Itara] Failed to instantiate OtelBridge "
                            + line + ": " + e.getMessage()
                            + ". Using no-op bridge.");
                }
            }
        } catch (IOException e) {
            log.warning("[Itara] Failed to read OtelBridge descriptor "
                    + url + ": " + e.getMessage() + ". Using no-op bridge.");
        }
        return new NoOpOtelBridge();
    }
}
