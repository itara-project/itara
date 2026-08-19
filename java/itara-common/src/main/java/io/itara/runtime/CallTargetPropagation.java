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

    public static final String HEADER_TARGET_METHOD    = "x-itara-target-method";

    private CallTargetPropagation() {}

    /**
     * Serializes the target's method into a header. A null method is
     * omitted, not encoded as empty.
     */
    public static Map<String, String> toHeaders(ItaraCallTarget target) {
        Map<String, String> headers = new HashMap<>();
        if (target.getMethod() != null) {
            headers.put(HEADER_TARGET_METHOD, target.getMethod());
        }
        return headers;
    }

    /**
     * Decodes the propagated method name from inbound headers, or null if
     * absent.
     */
    public static String decodeMethod(Map<String, String> headers) {
        return headers.get(HEADER_TARGET_METHOD);
    }
}
