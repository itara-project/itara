package io.itara.agent.config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal YAML parser for the Itara wiring config.
 *
 * Deliberately avoids external dependencies (no SnakeYAML, no Jackson)
 * to keep the agent jar self-contained and avoid classpath conflicts
 * with whatever the application uses.
 *
 * Supports only the specific structure of the wiring config.
 * This is not a general-purpose YAML parser.
 *
 * Config system property: -Ditara.config=/path/to/wiring.yaml
 */
public class ConfigLoader {

    public static final String CONFIG_PROPERTY = "itara.config";

    public static WiringConfig load() throws IOException {
        String path = System.getProperty(CONFIG_PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "[Itara] No wiring config specified. "
                    + "Start the JVM with -D" + CONFIG_PROPERTY + "=/path/to/config.yaml");
        }
        return parse(path);
    }

    static WiringConfig parse(String path) throws IOException {
        List<String> lines = readLines(path);
        WiringConfig config = new WiringConfig();
        config.setComponents(new ArrayList<>());
        config.setConnections(new ArrayList<>());

        String section = null;
        WiringConfig.ComponentEntry currentComponent = null;
        WiringConfig.ConnectionEntry currentConnection = null;

        for (String raw : lines) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;

            int indent = leadingSpaces(raw);
            String line = raw.strip();

            // Top-level section headers
            if (indent == 0) {
                if (line.equals("components:")) { section = "components"; continue; }
                if (line.equals("connections:")) { section = "connections"; continue; }
                continue;
            }

            // New list item
            if (line.startsWith("- ")) {
                String content = line.substring(2).strip();
                if ("components".equals(section)) {
                    currentComponent = new WiringConfig.ComponentEntry();
                    config.getComponents().add(currentComponent);
                    currentConnection = null;
                    parseKeyValue(content, currentComponent, null);
                } else if ("connections".equals(section)) {
                    currentConnection = new WiringConfig.ConnectionEntry();
                    config.getConnections().add(currentConnection);
                    currentComponent = null;
                    parseKeyValue(content, null, currentConnection);
                }
                continue;
            }

            // Property line within a list item
            if ("components".equals(section) && currentComponent != null) {
                parseKeyValue(line, currentComponent, null);
            } else if ("connections".equals(section) && currentConnection != null) {
                parseKeyValue(line, null, currentConnection);
            }
        }

        return config;
    }

    private static void parseKeyValue(String line,
                                      WiringConfig.ComponentEntry comp,
                                      WiringConfig.ConnectionEntry conn) {
        int colon = line.indexOf(':');
        if (colon < 0) return;
        String key = line.substring(0, colon).strip();
        String value = line.substring(colon + 1).strip();
        // Strip surrounding quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        if (comp != null) {
            switch (key) {
                case "id"        -> comp.setId(value);
            }
        } else if (conn != null) {
            switch (key) {
                case "from" -> conn.setFrom(value);
                case "to"   -> conn.setTo(value);
                case "type" -> conn.setType(value);
                case "host" -> conn.setHost(value);
                case "port" -> { try { conn.setPort(Integer.parseInt(value)); }
                                 catch (NumberFormatException ignored) {} }
            }
        }
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else break;
        }
        return count;
    }

    private static List<String> readLines(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }
}
