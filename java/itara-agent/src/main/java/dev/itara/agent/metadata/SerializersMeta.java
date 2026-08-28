package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional [serializers] section of a `.itara` metadata file.
 *
 * <p>Present on kind = "api" artifacts. Lists the serializers the artifact
 * was compiled with support for, each as an id + version range. Not
 * consumed yet — reserved for the wiring-time compatibility checks
 * called out as out-of-scope in the component-identity-from-.itara issue
 * (and, per ADR 0019/0020 follow-up work, expected to eventually be
 * evaluated by CLI tooling rather than the agent itself).
 *
 * <pre>{@code
 *   [serializers]
 *   supported = [
 *     { id = "json", version = "1.x" },
 *     { id = "protobuf", version = "1.x" },
 *   ]
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializersMeta {

    private List<SupportedSerializer> supported = new ArrayList<>();

    /** @return the serializers this artifact was compiled with support for; never null */
    public List<SupportedSerializer> getSupported() { return supported; }

    /** @param supported the serializers this artifact was compiled with support for; null is treated as empty */
    public void setSupported(List<SupportedSerializer> supported) {
        this.supported = supported != null ? supported : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "SerializersMeta{supported=" + supported + "}";
    }
}
