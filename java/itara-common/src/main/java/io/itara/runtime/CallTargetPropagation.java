package io.itara.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Serializes and deserializes ItaraCallTarget to and from transport
 * headers — the agent-owned mechanism referenced throughout spec §15/§16
 * as "conveyed independently of the serialized payload" (§7.5, §15.6,
 * §16.5). Mirrors {@link ContextPropagation}'s role for ItaraContext:
 * an entirely separate concern with its own header keys, merged into the
 * same outbound header map but never confused with it.
 *
 * A transport's own routing mechanism (e.g. an HTTP path) may also happen
 * to encode component/method for its own purposes — that is unrelated to
 * this class and not a source of truth for it in either direction.
 */
public final class CallTargetPropagation {

    public static final String HEADER_TARGET_NODE      = "x-itara-target-node";
    public static final String HEADER_TARGET_COMPONENT = "x-itara-target-component";
    public static final String HEADER_TARGET_METHOD    = "x-itara-target-method";

    private CallTargetPropagation() {}

    /**
     * Serializes the target into Itara-native transport headers.
     * A null field is omitted, not encoded as empty.
     */
    public static Map<String, String> toHeaders(ItaraCallTarget target) {
        Map<String, String> headers = new HashMap<>();
        if (target.getNode() != null)      headers.put(HEADER_TARGET_NODE, target.getNode());
        if (target.getComponent() != null) headers.put(HEADER_TARGET_COMPONENT, target.getComponent());
        if (target.getMethod() != null)    headers.put(HEADER_TARGET_METHOD, target.getMethod());
        return headers;
    }

    /**
     * Deserializes a target from inbound transport headers. Any field
     * absent from the headers comes back null — callers decide what
     * that means for them.
     */
    public static ItaraCallTarget fromHeaders(Map<String, String> headers) {
        return ItaraCallTarget.of(
                headers.get(HEADER_TARGET_NODE),
                headers.get(HEADER_TARGET_COMPONENT),
                headers.get(HEADER_TARGET_METHOD));
    }
}
