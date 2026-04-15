package io.itara.agent;

import io.itara.api.ComponentInterface;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the classpath for classes annotated with @ComponentInterface.
 *
 * The agent needs to know the actual Class object for each contract
 * interface so it can generate the correct proxy type for remote
 * connections. The component id in the annotation ties the class
 * to the id used in the wiring config.
 *
 * Scans all jars and directories on the classpath. For the PoC this
 * is a full scan — a future version could use an index in META-INF
 * to avoid scanning every class.
 */
public class ContractScanner {

    /**
     * Scans the classpath for @ComponentInterface-annotated classes.
     *
     * @return Map of component-id -> contract class
     */
    public static Map<String, Class<?>> scan(ClassLoader classLoader) {
        Map<String, Class<?>> result = new HashMap<>();

        // Get the classpath entries from the system class loader
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) return result;

        String[] entries = classpath.split(File.pathSeparator);
        for (String entry : entries) {
            File file = new File(entry);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                scanDirectory(file, file, classLoader, result);
            } else if (file.getName().endsWith(".jar")) {
                scanJar(file, classLoader, result);
            }
        }

        return result;
    }

    private static void scanDirectory(File root, File dir,
                                       ClassLoader classLoader,
                                       Map<String, Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(root, f, classLoader, result);
            } else if (f.getName().endsWith(".class")) {
                String className = toClassName(root, f);
                checkClass(className, classLoader, result);
            }
        }
    }

    private static void scanJar(File jarFile, ClassLoader classLoader,
                                  Map<String, Class<?>> result) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")
                        && !entry.getName().contains("$")) { // skip inner classes for now
                    String className = entry.getName()
                            .replace('/', '.')
                            .replace(".class", "");
                    checkClass(className, classLoader, result);
                }
            }
        } catch (Exception e) {
            // Skip unreadable jars — JDK jars, etc.
        }
    }

    private static void checkClass(String className, ClassLoader classLoader,
                                    Map<String, Class<?>> result) {
        // Skip known framework and JDK packages to avoid loading thousands
        // of classes unnecessarily
        if (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("jdk.")
                || className.startsWith("itara.")) {
            return;
        }

        try {
            Class<?> cls = classLoader.loadClass(className);
            ComponentInterface annotation = cls.getAnnotation(ComponentInterface.class);
            if (annotation != null) {
                String id = annotation.id();
                result.put(id, cls);
                System.out.println("[Itara] Found contract: " + id
                        + " -> " + className);
            }
        } catch (Throwable ignored) {
            // Many classes will fail to load due to missing dependencies
            // in the scanning context — this is expected and safe to skip
        }
    }

    private static String toClassName(File root, File classFile) {
        String rootPath = root.getAbsolutePath();
        String classPath = classFile.getAbsolutePath();
        return classPath
                .substring(rootPath.length() + 1)
                .replace(File.separatorChar, '.')
                .replace(".class", "");
    }
}
