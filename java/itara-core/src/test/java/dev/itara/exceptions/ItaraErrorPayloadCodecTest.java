package dev.itara.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ItaraErrorPayloadCodec")
public class ItaraErrorPayloadCodecTest {

    @Test
    @DisplayName("round-trips a fully populated payload")
    void roundtripsFullyPopulated() {
        ItaraErrorPayload payload = new ItaraErrorPayload(
                ItaraRemoteException.ErrorKind.CHECKED,
                "com.example.SomeCheckedException",
                "something went wrong");

        byte[] bytes = ItaraErrorPayloadCodec.encode(payload);
        ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(bytes);

        assertEquals(ItaraRemoteException.ErrorKind.CHECKED, decoded.getErrorKind());
        assertEquals("com.example.SomeCheckedException", decoded.getRemoteExceptionClass());
        assertEquals("something went wrong", decoded.getMessage());
    }

    @Test
    @DisplayName("round-trips a null message distinctly from an empty string")
    void roundtripsNullMessage() {
        ItaraErrorPayload withNull = new ItaraErrorPayload(
                ItaraRemoteException.ErrorKind.RUNTIME, "com.example.Boom", null);
        ItaraErrorPayload withEmpty = new ItaraErrorPayload(
                ItaraRemoteException.ErrorKind.RUNTIME, "com.example.Boom", "");

        ItaraErrorPayload decodedNull = ItaraErrorPayloadCodec.decode(ItaraErrorPayloadCodec.encode(withNull));
        ItaraErrorPayload decodedEmpty = ItaraErrorPayloadCodec.decode(ItaraErrorPayloadCodec.encode(withEmpty));

        assertNull(decodedNull.getMessage());
        assertEquals("", decodedEmpty.getMessage());
    }

    @Test
    @DisplayName("round-trips every ErrorKind value")
    void roundtripsEveryErrorKind() {
        for (ItaraRemoteException.ErrorKind kind : ItaraRemoteException.ErrorKind.values()) {
            ItaraErrorPayload payload = new ItaraErrorPayload(kind, "com.example.X", "msg");
            ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(ItaraErrorPayloadCodec.encode(payload));
            assertEquals(kind, decoded.getErrorKind(), "mismatch for " + kind);
        }
    }

    @Test
    @DisplayName("round-trips a large message content correctly (validates the 4-byte length prefix)")
    void roundtripsLargeMessage() {
        String large = "x".repeat(50_000);
        ItaraErrorPayload payload = new ItaraErrorPayload(
                ItaraRemoteException.ErrorKind.TRANSPORT, "com.example.Big", large);

        ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(ItaraErrorPayloadCodec.encode(payload));

        assertEquals(large, decoded.getMessage());
    }

    @Test
    @DisplayName("an unrecognized errorKind name falls back to TRANSPORT rather than failing to decode")
    void unrecognizedErrorKindFallsBackToTransport() throws IOException {
        byte[] bytes = handCraftPayloadBytesWithErrorKindName("SOME_FUTURE_KIND_NOT_YET_INVENTED");

        ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(bytes);

        assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, decoded.getErrorKind());
    }

    @Test
    @DisplayName("an unrecognized tag from a newer encoder is skipped without affecting known fields")
    void unknownTagIsSkippedGracefully() throws IOException {
        // Simulates bytes written by a hypothetical future codec version
        // that added a 4th field (tag 99) this version doesn't know about.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeStringField(out, (byte) 1, ItaraRemoteException.ErrorKind.CHECKED.name());
            writeStringField(out, (byte) 99, "some-future-field-value"); // unknown tag
            writeStringField(out, (byte) 2, "com.example.StillWorks");
            writeStringField(out, (byte) 3, "still readable");
        }

        ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(buffer.toByteArray());

        assertEquals(ItaraRemoteException.ErrorKind.CHECKED, decoded.getErrorKind());
        assertEquals("com.example.StillWorks", decoded.getRemoteExceptionClass());
        assertEquals("still readable", decoded.getMessage());
    }

    @Test
    @DisplayName("bytes missing a field (as if written by an older encoder) leave that field null")
    void missingFieldLeavesDefaultNull() throws IOException {
        // Simulates bytes written by a hypothetical older codec version
        // that predates the "message" field (tag 3) entirely.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeStringField(out, (byte) 1, ItaraRemoteException.ErrorKind.RUNTIME.name());
            writeStringField(out, (byte) 2, "com.example.OldException");
            // tag 3 (message) deliberately never written
        }

        ItaraErrorPayload decoded = ItaraErrorPayloadCodec.decode(buffer.toByteArray());

        assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, decoded.getErrorKind());
        assertEquals("com.example.OldException", decoded.getRemoteExceptionClass());
        assertNull(decoded.getMessage());
    }

    @Test
    @DisplayName("truncated bytes fail to decode with a clear, unchecked exception")
    void truncatedBytesThrow() {
        byte[] truncated = { 1, 1, 0, 0, 0 }; // tag=1, present=1, then an incomplete 4-byte length
        assertThrows(IllegalArgumentException.class, () -> ItaraErrorPayloadCodec.decode(truncated));
    }

    // ── Helpers for hand-crafting bytes in specific shapes ─────────────────

    private static byte[] handCraftPayloadBytesWithErrorKindName(String errorKindName) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeStringField(out, (byte) 1, errorKindName);
            writeStringField(out, (byte) 2, "com.example.Whatever");
            writeStringField(out, (byte) 3, "msg");
        }
        return buffer.toByteArray();
    }

    private static void writeStringField(DataOutputStream out, byte tag, String value) throws IOException {
        out.writeByte(tag);
        if (value == null) {
            out.writeByte(0);
        } else {
            out.writeByte(1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }
}
