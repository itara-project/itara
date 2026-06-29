package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * The [api-dependencies] section of a component `.itara` metadata file.
 *
 * Lists the synchronous API contracts this component calls, with the
 * exact version each was compiled against. Only meaningful on
 * kind = "component" artifacts.
 *
 * Absent means the component declares no outbound API calls — valid
 * for leaf components.
 *
 * Example TOML:
 *
 *   [api-dependencies]
 *   calls = [
 *     { id = "calculator", version = "1.0.0" },
 *     { id = "inventory",  version = "2.1.0" },
 *   ]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiDependenciesMeta {

    private List<ApiDependency> calls = Collections.emptyList();

    public List<ApiDependency> getCalls() { return calls; }
    public void setCalls(List<ApiDependency> calls) {
        this.calls = calls != null ? calls : Collections.emptyList();
    }

    public static ApiDependenciesMeta ofEmpty() {
        return new ApiDependenciesMeta();
    }

    @Override
    public String toString() {
        return "ApiDependenciesMeta{calls=" + calls + "}";
    }
}
