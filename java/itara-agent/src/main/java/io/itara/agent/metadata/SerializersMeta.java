package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional [serializers] section of a `.itara` metadata file.
 *
 * Present on kind = "api" artifacts. Lists the serializer ids the
 * artifact was compiled with support for. Not consumed yet — reserved
 * for the wiring-time compatibility checks called out as out-of-scope
 * in the component-identity-from-.itara issue.
 *
 *   [serializers]
 *   supported = ["json", "protobuf"]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SerializersMeta {

    private List<String> supported = new ArrayList<>();

    public List<String> getSupported() { return supported; }

    public void setSupported(List<String> supported) {
        this.supported = supported != null ? supported : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "SerializersMeta{supported=" + supported + "}";
    }
}
