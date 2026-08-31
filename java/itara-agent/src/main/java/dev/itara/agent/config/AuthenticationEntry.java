package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.Collections;
import java.util.Map;

/**
 * The authentication block of a connection entry in the wiring config.
 *
 * <p>Example YAML:
 * <pre>{@code
 * authentication:
 *   id: mtls
 *   params:
 *     trustStore: "/etc/itara/truststore.p12"
 * }</pre>
 *
 * <p>Absent means the noop implementation is used (§15.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthenticationEntry {

    /** Required for deserialization. */
    public AuthenticationEntry() {}

    @JsonSetter(nulls = Nulls.SKIP)
    private String id = "noop";

    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> params = Collections.emptyMap();

    /**
     * Returns the authentication type id.
     *
     * @return the authentication type id
     */
    public String getId() { return id; }
    /**
     * Sets the authentication type id.
     *
     * @param id the authentication type id
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
     * error (§15.4) — the block should either be omitted entirely
     * (defaulting to noop) or declare a real type identifier.
     *
     * @param connectionTo the connection's 'to' field, for the error message
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
