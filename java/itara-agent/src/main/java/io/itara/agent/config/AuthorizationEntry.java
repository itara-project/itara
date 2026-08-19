package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.Collections;
import java.util.Map;

/**
 * The authorization block of a connection entry in the wiring config.
 *
 * Example YAML:
 *
 *   authorization:
 *     id: rbac
 *     params:
 *       policyFile: "/etc/itara/policy.yaml"
 *
 * Absent means the noop implementation is used (§16.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizationEntry {

    @JsonSetter(nulls = Nulls.SKIP)
    private String id = "noop";

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * Validates this entry when the block is explicitly present on a
     * connection. A present block with a blank id is a configuration
     * error (§16.4) — the block should either be omitted entirely
     * (defaulting to noop) or declare a real type identifier.
     */
    public void validate(String connectionTo) {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + connectionTo
                            + "' declares an authorization block with a blank 'id'. "
                            + "Omit the block entirely to use the noop default, or supply a valid type identifier.");
        }
    }

    @Override
    public String toString() {
        return "AuthorizationEntry{id='" + id + "'}";
    }
}
