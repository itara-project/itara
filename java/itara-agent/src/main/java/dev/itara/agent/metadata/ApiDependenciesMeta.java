package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * The [api-dependencies] section of a component `.itara` metadata file.
 *
 * <p>Lists the synchronous API contracts this component calls, with the
 * exact version each was compiled against. Only meaningful on
 * kind = "component" artifacts.
 *
 * <p>Absent means the component declares no outbound API calls — valid
 * for leaf components.
 *
 * <p>Example TOML:
 * <pre>{@code
 * [api-dependencies]
 * calls = [
 *   { id = "calculator", version = "1.0.0" },
 *   { id = "inventory",  version = "2.1.0" },
 * ]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiDependenciesMeta {

    private List<ApiDependency> calls = Collections.emptyList();

    /** @return the API contracts this component depends on; never null */
    public List<ApiDependency> getCalls() { return calls; }
    /** @param calls the API contracts this component depends on; null is treated as empty */
    public void setCalls(List<ApiDependency> calls) {
        this.calls = calls != null ? calls : Collections.emptyList();
    }

    /** @return an instance with no declared API dependencies, for when the section is absent */
    public static ApiDependenciesMeta ofEmpty() {
        return new ApiDependenciesMeta();
    }

    @Override
    public String toString() {
        return "ApiDependenciesMeta{calls=" + calls + "}";
    }
}
