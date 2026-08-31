package dev.itara.agent;

import dev.itara.agent.config.ComponentNode;
import dev.itara.agent.config.WiringConfig;
import dev.itara.agent.exceptions.ActivatorCountException;
import dev.itara.agent.exceptions.ActivatorIdentityMismatchException;
import dev.itara.agent.exceptions.ActivatorNotFoundException;
import dev.itara.agent.exceptions.ComponentDirectoryNotFoundException;
import dev.itara.agent.exceptions.ComponentsDirectoryMisconfiguredException;
import dev.itara.agent.metadata.ItaraMetadataIndex;
import dev.itara.agent.metadata.MetadataException;
import dev.itara.agent.metadata.MetadataFile;
import dev.itara.api.ItaraActivator;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.logging.Logger;

/**
 * Discovers activator classes for every local component and owns the
 * classloader each one is activated (and dispatched) under.
 *
 * <p>Singleton, scanned exactly once per JVM via {@link #scan}, during agent
 * setup, before any component is registered with the registry. After a
 * successful scan, every local component id declared in the wiring config
 * is guaranteed to have both an {@link ActivatedComponent} and a
 * classloader registered — scan() verifies this itself, per component,
 * failing fast with a specific, actionable error rather than a generic
 * "some activator is missing" message discovered later.
 *
 * <p>Isolated vs. shared mode is strictly opt-in: isolated mode is used if
 * and only if the {@code ITARA_COMPONENTS_DIR} environment variable is
 * set at all. There is no default directory and no fallback — an
 * accidental isolated-mode run, triggered only because some directory
 * happened to exist at a default path, would be exactly the kind of
 * silent misconfiguration that is hard to track down later, so isolation
 * requires deliberately setting the environment variable. The two modes
 * are mutually exclusive.
 *
 * <p>In isolated mode, each local component's classloader is resolved by
 * deliberately looking for a subdirectory named after the component id —
 * never by enumerating whatever subdirectories happen to be present. A
 * component directory's name is required to exactly match its component
 * id; a missing or misnamed directory is reported immediately, naming the
 * specific component, rather than surfacing later as an unrelated-looking
 * failure.
 *
 * <p>Each component jar includes a file at:
 *   META-INF/itara/activator
 *
 * containing a single line: the fully qualified class name of the
 * activator, in the same format as META-INF/itara/transport,
 * META-INF/itara/serializer, etc.
 *
 * <p>Component identity (id, version, api-version) is not read from this
 * file. Once the activator class is loaded, its jar is resolved via the
 * classloader's codesource, mapped to a version-independent artifact
 * name, and looked up in the metadata index built from
 * -Ditara.metadata.dir (see ItaraMetadataIndex). A missing or
 * unresolvable `.itara` entry is a fatal error.
 */
public class ActivatorScanner {

    /** Set to enable isolated mode; unset means shared mode. */
    public static final String COMPONENTS_DIR_ENV_VAR = "ITARA_COMPONENTS_DIR";
    private static final String RESOURCE_PATH = "META-INF/itara/activator";

    private static final Logger log = Logger.getLogger(ActivatorScanner.class.getName());

    private static final ActivatorScanner INSTANCE = new ActivatorScanner();

    private final Map<String, ActivatedComponent> activators = new HashMap<>();
    private final Map<String, ClassLoader> classLoaders = new HashMap<>();
    private boolean isolated;
    private boolean scanned;

    private ActivatorScanner() {
    }

    /**
     * Returns the singleton scanner instance.
     *
     * @return the singleton scanner instance
     */
    public static ActivatorScanner instance() {
        return INSTANCE;
    }



    /**
     * Scans for activators for every local component in the wiring
     * config. Must be called exactly once per JVM, during agent setup,
     * before any component is registered with the registry.
     *
     * @param systemClassLoader the JVM's system classloader — used as the
     *                          parent for every per-component classloader
     *                          in isolated mode, and scanned directly in
     *                          shared mode.
     * @param wiringConfig      the loaded wiring config, used to determine
     *                          which component ids are local to this JVM slice
     */

    public void scan(ClassLoader systemClassLoader, WiringConfig wiringConfig) {
        scan(systemClassLoader, wiringConfig, System.getenv(COMPONENTS_DIR_ENV_VAR));
    }

    /**
     * Visible for testing — same as {@link #scan(ClassLoader, WiringConfig)}
     * but takes the components-directory path directly rather than reading
     * it from the real process environment, which cannot be mutated from
     * within a running JVM. Lets tests drive isolated vs. shared mode
     * deterministically.
     */
    synchronized void scan(ClassLoader systemClassLoader, WiringConfig wiringConfig, String componentsDirPath) {
        if (scanned) {
            throw new IllegalStateException("[Itara] ActivatorScanner.scan() called more than once.");
        }
        scanned = true;

        Set<String> localComponentIds = new LinkedHashSet<>();
        for (ComponentNode node : wiringConfig.getLocalNodes()) {
            localComponentIds.add(node.getComponent());
        }

        isolated = componentsDirPath != null;

        log.info("[Itara] activator scan mode=" + (isolated ? "isolated" : "shared"));

        if (isolated) {
            File componentsDir = new File(componentsDirPath);
            if (!componentsDir.isDirectory()) {
                throw new ComponentsDirectoryMisconfiguredException(COMPONENTS_DIR_ENV_VAR, componentsDirPath);
            }
            scanIsolated(systemClassLoader, componentsDir, localComponentIds);
        } else {
            scanShared(systemClassLoader, localComponentIds);
        }
    }

    private void scanIsolated(ClassLoader systemClassLoader, File componentsDir, Set<String> localComponentIds) {
        for (String componentId : localComponentIds) {
            File componentDir = new File(componentsDir, componentId);
            if (!componentDir.isDirectory()) {
                throw new ComponentDirectoryNotFoundException(componentId, componentDir.getPath());
            }

            ClassLoader componentClassLoader = buildComponentClassLoader(componentId, componentDir, systemClassLoader);
            Map<String, ActivatedComponent> found = scanClassLoader(componentClassLoader);

            if (found.size() != 1) {
                throw new ActivatorCountException(componentId, componentDir.getPath(), found.size());
            }

            ActivatedComponent activated = found.get(componentId);
            if (activated == null) {
                String actualComponentId = found.keySet().iterator().next();
                throw new ActivatorIdentityMismatchException(componentId, actualComponentId, componentDir.getPath());
            }

            activators.put(componentId, activated);
            classLoaders.put(componentId, componentClassLoader);
            log.fine("[Itara] registered activator component=" + componentId
                    + " mode=isolated dir=" + componentDir.getPath());
        }
    }

    private ClassLoader buildComponentClassLoader(String componentId, File componentDir, ClassLoader systemClassLoader) {
        File[] jars = componentDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IllegalStateException(
                    "[Itara] Component directory for '" + componentId + "' at " + componentDir.getPath()
                            + " contains no jar files.");
        }

        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            try {
                urls[i] = jars[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(
                        "[Itara] Failed to build classloader for component '" + componentId
                                + "' — could not resolve jar " + jars[i], e);
            }
        }
        // Plain URLClassLoader is parent-first by default — no custom
        // subclass needed for the parent-first delegation this model
        // depends on. See ADR-0018.
        return new URLClassLoader(urls, systemClassLoader);
    }

    private void scanShared(ClassLoader systemClassLoader, Set<String> localComponentIds) {
        Map<String, ActivatedComponent> found = scanClassLoader(systemClassLoader);

        for (String componentId : localComponentIds) {
            ActivatedComponent activated = found.get(componentId);
            if (activated == null) {
                throw new ActivatorNotFoundException(componentId);
            }
            activators.put(componentId, activated);
            classLoaders.put(componentId, systemClassLoader);
            log.fine("[Itara] registered activator component=" + componentId + " isolated=false");
        }

        for (String discoveredComponentId : found.keySet()) {
            if (!localComponentIds.contains(discoveredComponentId)) {
                log.warning("[Itara] activator found for component=" + discoveredComponentId
                        + " but it is not a local node in the wiring config — it will not be activated. "
                        + "Check that this is expected; an unused activator jar on the classpath usually "
                        + "indicates a stale dependency or a missing wiring entry.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, ActivatedComponent> scanClassLoader(ClassLoader classLoader) {
        Map<String, ActivatedComponent> result = new HashMap<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                log.fine("[Itara] found activator descriptor url=" + url);

                String activatorClassName = readActivatorClassName(url);
                Class<?> raw = classLoader.loadClass(activatorClassName);
                if (!ItaraActivator.class.isAssignableFrom(raw)) {
                    throw new IllegalStateException(
                            "[Itara] Class " + activatorClassName
                                    + " does not implement ItaraActivator. "
                                    + "Check META-INF/itara/activator in the component jar.");
                }
                Class<? extends ItaraActivator> activatorClass = (Class<? extends ItaraActivator>) raw;

                MetadataFile metadata = resolveMetadata(activatorClass, url);
                ActivatedComponent component = new ActivatedComponent(activatorClass, metadata);

                result.put(component.getComponentId(), component);
                log.fine("[Itara] discovered activator component=" + component);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("[Itara] Failed to scan for activators", e);
        }
        return result;
    }

    /**
     * Reads the single-line activator descriptor: the fully qualified
     * class name of the activator. Blank lines and '#' comments are
     * skipped, matching TransportLoader/SerializerLoader.
     *
     * Visible for testing.
     */
    static String readActivatorClassName(URL url) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                return line;
            }
        }
        throw new IllegalStateException(
                "[Itara] Empty activator descriptor at " + url
                        + " — expected a single line containing the activator's "
                        + "fully qualified class name.");
    }

    /**
     * Resolves the jar that the given activator class was loaded from,
     * derives its version-independent artifact name, and looks up the
     * corresponding `.itara` metadata file in the metadata index.
     *
     * @throws MetadataException if the jar cannot be resolved (no
     *                            codesource / not a file URL) or if no
     *                            matching `.itara` entry is found.
     */
    private static MetadataFile resolveMetadata(Class<? extends ItaraActivator> activatorClass, URL descriptorUrl) {
        ProtectionDomain protectionDomain = activatorClass.getProtectionDomain();
        CodeSource codeSource = (protectionDomain != null) ? protectionDomain.getCodeSource() : null;
        URL location = (codeSource != null) ? codeSource.getLocation() : null;

        if (location == null) {
            throw new MetadataException(
                    "[Itara] Cannot resolve component identity for activator '"
                            + activatorClass.getName() + "' (descriptor: " + descriptorUrl + "): "
                            + "no codesource available for its jar.");
        }

        String jarFileName = new File(location.getPath()).getName();
        String artifactName = ItaraMetadataIndex.versionIndependentNameFromJar(jarFileName);

        return ItaraMetadataIndex.instance().lookup(artifactName)
                .orElseThrow(() -> new MetadataException(
                        "[Itara] No '" + artifactName + ".itara' file found in "
                                + ItaraMetadataIndex.METADATA_DIR_PROPERTY + " for activator '"
                                + activatorClass.getName() + "' (jar: " + jarFileName + ")."));
    }

    // ── Query API ────────────────────────────────────────────────────────

    /**
     * Whether the most recent scan() resolved isolated mode. Meaningless
     * before scan() has been called.
     *
     * @return whether the most recent scan() resolved isolated mode
     */
    public boolean isIsolated() {
        return isolated;
    }

    /**
     * Returns the activated component for the given local component id.
     * scan() guarantees every local component id has an entry; calling
     * this for a component id that isn't local, or before scan() has
     * succeeded, is a programming error.
     *
     * @param componentId the local component id to look up
     * @return the activated component for the given id
     */
    public ActivatedComponent getActivatedComponent(String componentId) {
        ActivatedComponent activated = activators.get(componentId);
        if (activated == null) {
            throw new IllegalStateException(
                    "[Itara] No activator registered for component '" + componentId
                            + "'. This is a programming error — scan() should have already "
                            + "guaranteed this for every local component.");
        }
        return activated;
    }

    /**
     * Returns the classloader the given local component was activated
     * under (its own isolated classloader in isolated mode, the system
     * classloader in shared mode). Same guarantees as
     * {@link #getActivatedComponent}.
     *
     * @param componentId the local component id to look up
     * @return the classloader the given component was activated under
     */
    public ClassLoader getClassLoader(String componentId) {
        ClassLoader classLoader = classLoaders.get(componentId);
        if (classLoader == null) {
            throw new IllegalStateException(
                    "[Itara] No classloader registered for component '" + componentId
                            + "'. This is a programming error — scan() should have already "
                            + "guaranteed this for every local component.");
        }
        return classLoader;
    }

    /**
     * Visible for testing — clears all discovered state so scan() can be
     * called again within the same JVM.
     */
    public void reset() {
        if (isolated) {
            for (ClassLoader classLoader : classLoaders.values()) {
                if (classLoader instanceof Closeable closeable) {
                    try {
                        closeable.close();
                    } catch (IOException e) {
                        log.warning("[Itara] failed to close component classloader during reset: " + e.getMessage());
                    }
                }
            }
        }
        activators.clear();
        classLoaders.clear();
        isolated = false;
        scanned = false;
    }
}
