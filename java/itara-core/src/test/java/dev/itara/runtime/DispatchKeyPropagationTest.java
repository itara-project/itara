package dev.itara.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DispatchKeyPropagation")
class DispatchKeyPropagationTest {

    @Nested
    @DisplayName("encode()")
    class Encode {

        @Test
        @DisplayName("adds the connectionId under the expected header name")
        void addsExpectedHeader() {
            Map<String, String> headers = new HashMap<>();

            headers.putAll(DispatchKeyPropagation.encode("conn-order-to-calc"));

            assertEquals("conn-order-to-calc",
                    headers.get(DispatchKeyPropagation.HEADER_DISPATCH_KEY));
        }

        @Test
        @DisplayName("does not disturb other entries already in the headers map")
        void preservesOtherHeaders() {
            Map<String, String> headers = new HashMap<>();
            headers.put("x-itara-trace-id", "abc123");

            headers.putAll(DispatchKeyPropagation.encode("conn-order-to-calc"));

            assertEquals("abc123", headers.get("x-itara-trace-id"));
            assertEquals(2, headers.size());
        }

        @Test
        @DisplayName("rejects a null key")
        void rejectsNullKey() {
            assertThrows(NullPointerException.class,
                    () -> DispatchKeyPropagation.encode(null));
        }
    }

    @Nested
    @DisplayName("decode()")
    class Decode {

        @Test
        @DisplayName("recovers a key equal to the one that was encoded")
        void roundTrips() {
            Map<String, String> headers = new HashMap<>();
            String original = "conn-order-to-calc";

            headers.putAll(DispatchKeyPropagation.encode(original));
            String decoded = DispatchKeyPropagation.decode(headers);

            assertEquals(original, decoded);
        }

        @Test
        @DisplayName("throws when the header is missing — fails closed, does not guess")
        void throwsWhenHeaderMissing() {
            Map<String, String> headers = new HashMap<>();
            headers.put("x-itara-trace-id", "abc123"); // present, but not the connection-id header

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> DispatchKeyPropagation.decode(headers));

            assertTrue(ex.getMessage().contains(DispatchKeyPropagation.HEADER_DISPATCH_KEY));
        }

        @Test
        @DisplayName("rejects a null headers map")
        void rejectsNullHeaders() {
            assertThrows(NullPointerException.class,
                    () -> DispatchKeyPropagation.decode(null));
        }
    }
}
