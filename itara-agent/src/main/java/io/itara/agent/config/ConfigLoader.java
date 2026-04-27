package io.itara.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and parses the Itara wiring configuration from a YAML file.
 *
 * The config file path is specified via the JVM system property:
 *   -Ditara.config=/path/to/wiring.yaml
 *
 * Loading happens in two phases:
 *
 *   1. Environment variable substitution — applied to the raw file content
 *      before parsing. Syntax: ${VAR_NAME:-default_value}
 *      This allows container deployments to inject host names, ports, and
 *      other environment-specific values without modifying the config file.
 *
 *   2. YAML parsing — Jackson maps the substituted YAML into WiringConfig,
 *      ComponentEntry, and ConnectionEntry objects. Unknown fields are
 *      silently ignored for forward compatibility.
 *
 * After parsing, the config is validated — required fields are checked and
 * transport-specific requirements (e.g. host/port for HTTP) are enforced.
 * Validation errors throw ConfigurationException with a message that
 * identifies the exact field and connection index.
 */
public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());

    public static final String CONFIG_PROPERTY = "itara.config";

    /** Matches ${VAR_NAME} and ${VAR_NAME:-default} */
    private static final Pattern ENV_VAR_PATTERN =
            Pattern.compile("\\$\\{([^}:]+)(?::-(.*?))?}");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Loads the wiring config from the path specified by -Ditara.config.
     *
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
        return parse(path);
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

        WiringConfig config;
        try {
            config = MAPPER.readValue(substituted, WiringConfig.class);
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
     * Substitution happens on the raw string so the parser always sees
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
                log.warning("[Itara] Environment variable '" + varName
                        + "' is not set and has no default. "
                        + "Placeholder '" + matcher.group() + "' will be used as-is.");
                replacement = matcher.group();
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
