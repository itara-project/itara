package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.Collections;
import java.util.Map;

/**
 * The authentication block of a connection entry in the wiring config.
 *
 * Example YAML:
 *
 *   authentication:
 *     id: mtls
 *     params:
 *       trustStore: "/etc/itara/truststore.p12"
 *
 * Absent means the noop implementation is used (§15.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthenticationEntry {

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
     * error (§15.4) — the block should either be omitted entirely
     * (defaulting to noop) or declare a real type identifier.
     */
    public void validate(String connectionTo) {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException(
                    "[Itara] Connection to='" + connectionTo
                            + "' declares an authentication block with a blank 'id'. "
                            + "Omit the block entirely to use the noop default, or supply a valid type identifier.");
        }
    }

    @Override
    public String toString() {
        return "AuthenticationEntry{id='" + id + "'}";
    }
}
