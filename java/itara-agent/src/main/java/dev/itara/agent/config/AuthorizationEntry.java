package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.Collections;
import java.util.Map;

/**
 * The authorization block of a connection's callee block in the wiring
 * config. Authorization is callee-side only — it is purely the callee's own
 * access-control decision.
 *
 * <p>Example YAML:
 * <pre>{@code
 * authorization:
 *   id: rbac
 *   params:
 *     policyFile: "/etc/itara/policy.yaml"
 * }</pre>
 *
 * <p>Absent means the noop implementation is used (§16.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizationEntry {

    /** Required for deserialization. */
    public AuthorizationEntry() {}

    @JsonSetter(nulls = Nulls.SKIP)
    private String id = "noop";

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    /**
     * Returns the authorization type id.
     *
     * @return the authorization type id
     */
    public String getId() { return id; }
    /**
     * Sets the authorization type id.
     *
     * @param id the authorization type id
     */
    public void setId(String id) { this.id = id; }

    /**
     * Returns implementation-specific connection parameters; never null.
     *
     * @return implementation-specific connection parameters; never null
     */
    public Map<String, String> getParams() { return params; }
    /**
     * Sets the implementation-specific connection parameters.
     *
     * @param params implementation-specific connection parameters; null is treated as empty
     */
    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * Validates this entry when the block is explicitly present on a
     * connection. A present block with a blank id is a configuration
     * error (§16.4) — the block should either be omitted entirely
     * (defaulting to noop) or declare a real type identifier.
     *
     * @param owner where this block was declared, for the error message —
     *              e.g. "Connection id='x' (callee)"
     */
    public void validate(String owner) {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] " + owner
                            + " declares an authorization block with a blank 'id'. "
                            + "Omit the block entirely to use the noop default, or supply a valid type identifier.");
        }
    }

    @Override
    public String toString() {
        return "AuthorizationEntry{id='" + id + "'}";
    }
}
