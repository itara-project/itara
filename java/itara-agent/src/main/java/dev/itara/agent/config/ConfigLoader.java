package dev.itara.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and parses the Itara wiring configuration from a YAML file.
 *
 * <p>The config file path is specified via the JVM system property:
 *   -Ditara.config=/path/to/wiring.yaml
 *
 * <p>Loading happens in two phases:
 *
 * <p>1. Environment variable substitution — applied to the raw file content
 *      before parsing. Syntax: ${VAR_NAME:-default_value}
 *      This allows container deployments to inject host names, ports, and
 *      other environment-specific values without modifying the config file.
 *
 * <p>2. YAML parsing — Jackson maps the substituted YAML into WiringConfig
 *      and its constituent Node (ComponentNode, VirtualNode) and
 *      ConnectionEntry objects. Unknown fields are silently ignored for
 *      forward compatibility.
 *
 * <p>After parsing, the config is validated — required fields are checked and
 * transport-specific requirements (e.g. host/port for HTTP) are enforced.
 * Validation errors throw ConfigurationException with a message that
 * identifies the exact field and connection index.
 */
public class ConfigLoader {

    /** Not instantiated — all methods are static. */
    private ConfigLoader() {}

    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());

    /** JVM system property naming the wiring config file path. */
    public static final String CONFIG_PROPERTY = "itara.config";
    /** JVM system property naming this JVM slice's local node ids. */
    public static final String NODES_PROPERTY = "itara.nodes";

    /** Matches ${VAR_NAME} and ${VAR_NAME:-default} */
    private static final Pattern ENV_VAR_PATTERN =
            Pattern.compile("\\$\\{([^}:]+)(?::-(.*?))?}");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private static final Yaml SNAKE_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Loads the wiring config from the path specified by -Ditara.config.
     *
     * @return the loaded and validated wiring config
     * @throws IllegalStateException  if the system property is not set
     * @throws IOException            if the file cannot be read
     * @throws ConfigurationException if the YAML is malformed or required
     *                                fields are missing
     */
    public static WiringConfig load() throws IOException {
        String path = System.getProperty(CONFIG_PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "[Itara] No wiring config specified. "
                    + "Start the JVM with -D" + CONFIG_PROPERTY + "=/path/to/config.yaml");
        }

        String nodes = System.getProperty(NODES_PROPERTY);
        if (nodes == null || nodes.isBlank()) {
            throw new IllegalStateException(
                    "[Itara] No nodes specified. "
                            + "Start the JVM with -D" + NODES_PROPERTY + "=node1,node2");
        }
        List<String> nodeIds = Arrays.stream(nodes.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toList();
        if (nodeIds.isEmpty()) {
            throw new IllegalStateException("[Itara] Nodes cannot be parsed. "
                    + "Check that you start the JVM with -D" + NODES_PROPERTY + "=node1,node2. "
                    + "Current value is: " + nodes);
        }
        return relevantPartOf(parse(path), nodeIds);
    }

    static WiringConfig relevantPartOf(WiringConfig fullConfig, List<String> nodeIds) {
        WiringConfig relevantConfig = new WiringConfig();
        List<ConnectionEntry> connections = fullConfig.getConnections().stream()
                .filter(conn -> conn.isRelatedToAnyOfNodes(nodeIds))
                .toList();
        Set<String> relevantNodeIds = new HashSet<>();
        connections.forEach(connectionEntry -> {
            if (connectionEntry.getFrom() != null
                    && !connectionEntry.getFrom().isBlank()) {
                relevantNodeIds.add(connectionEntry.getFrom());
            }
            relevantNodeIds.add(connectionEntry.getTo());
        });
        relevantConfig.setConnections(connections);
        relevantConfig.setNodes(fullConfig.getNodes().stream()
                .filter(n -> relevantNodeIds.contains(n.getId()))
                .toList());
        relevantConfig.setLocalNodeIds(nodeIds);
        relevantConfig.validate();
        return relevantConfig;
    }

    /**
     * Parses a wiring config from the given file path.
     * Visible for testing.
     */
    static WiringConfig parse(String path) throws IOException {
        String raw;
        try {
            raw = Files.readString(Paths.get(path));
        } catch (IOException e) {
            throw new IOException(
                    "[Itara] Could not read wiring config from '"
                    + path + "': " + e.getMessage(), e);
        }
        return parseString(raw);
    }

    /**
     * Parses a wiring config from a raw YAML string.
     * Visible for testing — allows testing without a file on disk.
     * Environment variable substitution is applied before parsing.
     *
     * @throws ConfigurationException if the YAML is malformed or required
     *                                fields are missing
     */
    static WiringConfig parseString(String yaml) {
        String substituted = substituteEnvVars(yaml);

        // Empty or comment-only documents produce no content — return empty config
        if (substituted == null || substituted.isBlank()
                || substituted.lines()
                .map(String::strip)
                .allMatch(l -> l.isEmpty() || l.startsWith("#"))) {
            return new WiringConfig();
        }

        // SnakeYAML's Composer resolves anchors, aliases, and merge keys (<<) before
        // Jackson sees the document. Jackson's YAMLParser intercepts alias tokens and
        // converts them to their anchor name as a plain string, bypassing resolution
        // entirely — so we must resolve first, then deserialize from the resolved object.
        Object resolved;
        try {
            resolved = SNAKE_YAML.load(substituted);
        } catch (Exception e) {
            throw new ConfigurationException(
                    "[Itara] Failed to parse wiring config: " + e.getMessage(), e);
        }

        if (resolved == null) {
            return new WiringConfig();
        }

        WiringConfig config;
        try {
            config = MAPPER.convertValue(resolved, WiringConfig.class);
        } catch (Exception e) {
            throw new ConfigurationException(
                    "[Itara] Failed to parse wiring config: " + e.getMessage(), e);
        }

        // readValue returns null for an empty or comment-only document
        if (config == null) {
            config = new WiringConfig();
        }

        config.validate();
        return config;
    }

    // ── Env var substitution ───────────────────────────────────────────────

    /**
     * Substitutes ${VAR:-default} and ${VAR} patterns in the raw YAML
     * string before it is handed to the YAML parser.
     *
     * <p>Substitution happens on the raw string so the parser always sees
     * clean, well-typed content. A port substituted from an env var
     * arrives as a plain integer string, which Jackson coerces to int.
     */
    static String substituteEnvVars(String raw) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(raw);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName    = matcher.group(1);
            String defaultVal = matcher.group(2); // null if no :- present
            String envValue   = System.getenv(varName);

            String replacement;
            if (envValue != null) {
                replacement = envValue;
            } else if (defaultVal != null) {
                replacement = defaultVal;
            } else {
                log.warning("[Itara] Environment variable='" + varName
                        + "' is not set and has no default. "
                        + "Placeholder='" + matcher.group() + "' will be used as-is.");
                replacement = matcher.group();
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
