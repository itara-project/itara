package io.itara.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes/decodes a DispatchKey as a single plain header, for transports
 * that already carry an opaque string-to-string headers map end-to-end (as
 * Kafka and HTTP already do for observability context propagation).
 *
 * Deliberately one single, human-typable header — not a compound or encoded
 * value — so a connection can be addressed by hand in a cURL request or a
 * Postman collection, with nothing to compute.
 *
 * This is optional scaffolding, not part of the DispatchHandler/ItaraTransport
 * contract itself. A transport that propagates the key some other way (e.g.
 * as part of an HTTP URL path segment) is free to build/read a DispatchKey
 * directly and skip this utility entirely.
 */
public final class DispatchKeyPropagation {

    public static final String HEADER_DISPATCH_KEY = "x-itara-dispatch-key";

    private DispatchKeyPropagation() {
    }

    /**
     * Creates a map with the encoded dispatch key.
     */
    public static Map<String, String> encode(String key) {
        Objects.requireNonNull(key, "[Itara] DispatchKeyPropagation.encode() requires a non-null key.");
        Map<String, String> headers = new HashMap<>();
        headers.put(HEADER_DISPATCH_KEY, key);
        return headers;
    }

    /**
     * Decodes a dispatch key from the given headers map.
     *
     * @throws IllegalArgumentException if the header is missing — a missing
     *         key means routing cannot proceed; callers should fail closed,
     *         not guess.
     */
    public static String decode(Map<String, String> headers) {
        Objects.requireNonNull(headers, "[Itara] DispatchKeyPropagation.decode() requires a non-null headers map.");
        String key = headers.get(HEADER_DISPATCH_KEY);
        if (key == null) {
            throw new IllegalArgumentException(
                    "[Itara] Missing required header '" + HEADER_DISPATCH_KEY + "' — cannot determine which "
                            + "connection this request belongs to.");
        }
        return key;
    }
}
