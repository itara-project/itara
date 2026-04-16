package io.itara.agent;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Child-first classloader for Itara runtime jars.
 *
 * Loads jars from the directory specified by -Ditara.lib.dir.
 * Uses child-first delegation so Itara runtime jars can use
 * different dependency versions than the application.
 *
 * Classes from itara-common (ItaraTransport, ItaraActivator,
 * ItaraRegistry, etc.) are intentionally NOT in the lib dir,
 * so they fall through to the parent (system) classloader.
 * This preserves the shared interface boundary — the application
 * and the Itara runtime always see the same interface classes.
 *
 * If -Ditara.lib.dir is not set or the directory is empty,
 * falls back to the provided parent classloader transparently.
 */
public class ItaraClassLoader extends URLClassLoader {

    public static final String LIB_DIR_PROPERTY = "itara.lib.dir";

    static {
        // Register as parallel-capable for better performance
        ClassLoader.registerAsParallelCapable();
    }

    private ItaraClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * Builds the Itara classloader from -Ditara.lib.dir.
     * Returns the parent classloader unchanged if the property
     * is not set or the directory contains no jars.
     */
    public static ClassLoader build(ClassLoader parent) {
        String libDir = System.getProperty(LIB_DIR_PROPERTY);
        if (libDir == null || libDir.isBlank()) {
            System.out.println("[Itara] No itara.lib.dir set — "
                    + "transports and plugins must be on the classpath.");
            return parent;
        }

        File dir = new File(libDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("[Itara] WARNING: itara.lib.dir does not exist "
                    + "or is not a directory: " + libDir);
            return parent;
        }

        File[] jars = dir.listFiles(f ->
                f.isFile() && f.getName().endsWith(".jar"));

        if (jars == null || jars.length == 0) {
            System.out.println("[Itara] WARNING: itara.lib.dir is empty: "
                    + libDir);
            return parent;
        }

        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            try {
                urls[i] = jars[i].toURI().toURL();
                System.out.println("[Itara] Loading lib: " + jars[i].getName());
            } catch (Exception e) {
                throw new RuntimeException(
                        "[Itara] Failed to resolve jar URL: " + jars[i], e);
            }
        }

        System.out.println("[Itara] Loaded " + jars.length
                + " lib(s) from: " + libDir);
        return new ItaraClassLoader(urls, parent);
    }

    /**
     * Child-first delegation.
     * Try our jars first, fall back to parent for anything we don't have.
     * This allows Itara libs to use different dependency versions
     * than the application without conflict.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {

        synchronized (getClassLoadingLock(name)) {
            // Already loaded?
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            // Try our jars first (child-first)
            try {
                Class<?> found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            } catch (ClassNotFoundException ignored) {
                // Not in our jars — fall through to parent
            }

            // Fall back to parent (system classloader)
            return super.loadClass(name, resolve);
        }
    }
}