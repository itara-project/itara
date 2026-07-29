package io.itara.serializer.protobuf;

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.itara.exceptions.ItaraErrorPayload;
import io.itara.exceptions.ItaraRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses com.google.protobuf.StringValue / Int32Value (from wrappers.proto,
 * bundled directly in the protobuf-java jar) as stand-in GeneratedMessageV3
 * business payloads. No protoc step or example .proto contract exists in
 * this module yet so these well-known, already-compiled types are enough to
 * exercise the serializer generically without any per-message-type code.
 */
@DisplayName("ProtoItaraSerializer")
class ProtoItaraSerializerTest {

    private static final ProtoSerializerConfig TEST_CONFIG = ProtoSerializerConfig.INSTANCE;

    private ProtoItaraSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new ProtoItaraSerializer();
    }

    @Test
    @DisplayName("type() returns 'protobuf'")
    void type() {
        assertEquals("protobuf", serializer.type());
    }

    @Nested
    @DisplayName("Args")
    class Args {

        @Test
        @DisplayName("round-trips a single GeneratedMessageV3 argument")
        void roundtripsSingleArgument() throws Exception {
            StringValue arg = StringValue.newBuilder().setValue("hello").build();

            byte[] bytes = serializer.serializeArgs(new Object[]{ arg }, TEST_CONFIG);
            Object[] result = serializer.deserializeArgs(bytes, new Class<?>[]{ StringValue.class }, TEST_CONFIG);

            assertEquals(1, result.length);
            assertEquals(arg, result[0]);
        }

        @Test
        @DisplayName("no-argument method produces and expects an empty byte array")
        void noArguments() throws Exception {
            byte[] bytes = serializer.serializeArgs(new Object[0], TEST_CONFIG);
            assertArrayEquals(new byte[0], bytes);

            Object[] result = serializer.deserializeArgs(bytes, new Class<?>[0], TEST_CONFIG);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("null args array is treated the same as zero-length")
        void nullArgsArray() throws Exception {
            byte[] bytes = serializer.serializeArgs(null, TEST_CONFIG);
            assertArrayEquals(new byte[0], bytes);
        }

        @Test
        @DisplayName("serializeArgs rejects more than one argument")
        void rejectsMultipleArgumentsOnSerialize() {
            StringValue a = StringValue.newBuilder().setValue("a").build();
            StringValue b = StringValue.newBuilder().setValue("b").build();

            assertThrows(IllegalArgumentException.class,
                    () -> serializer.serializeArgs(new Object[]{ a, b }, TEST_CONFIG));
        }

        @Test
        @DisplayName("deserializeArgs rejects more than one declared parameter")
        void rejectsMultipleParamTypesOnDeserialize() {
            assertThrows(IllegalArgumentException.class,
                    () -> serializer.deserializeArgs(new byte[0],
                            new Class<?>[]{ StringValue.class, Int32Value.class }, TEST_CONFIG));
        }

        @Test
        @DisplayName("serializeArgs rejects a non-GeneratedMessageV3 argument")
        void rejectsNonProtoArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> serializer.serializeArgs(new Object[]{ "plain string" }, TEST_CONFIG));
        }

        @Test
        @DisplayName("deserializeArgs rejects a non-GeneratedMessageV3 declared parameter type")
        void rejectsNonProtoParameterType() {
            assertThrows(IllegalArgumentException.class,
                    () -> serializer.deserializeArgs(new byte[0], new Class<?>[]{ String.class }, TEST_CONFIG));
        }
    }

    @Nested
    @DisplayName("Result")
    class Result {

        @Test
        @DisplayName("round-trips a GeneratedMessageV3 return value")
        void roundtripsResult() throws Exception {
            Int32Value value = Int32Value.newBuilder().setValue(42).build();

            byte[] bytes = serializer.serializeResult(value, TEST_CONFIG);
            Object result = serializer.deserializeResult(bytes, Int32Value.class, TEST_CONFIG);

            assertEquals(value, result);
        }

        @Test
        @DisplayName("null result (void method) serializes as an empty byte array")
        void nullResult() throws Exception {
            byte[] bytes = serializer.serializeResult(null, TEST_CONFIG);
            assertArrayEquals(new byte[0], bytes);
        }

        @Test
        @DisplayName("Void.TYPE always deserializes to null regardless of payload")
        void voidReturnType() throws Exception {
            Object result = serializer.deserializeResult(new byte[0], Void.TYPE, TEST_CONFIG);
            assertNull(result);
        }

        @Test
        @DisplayName("serializeResult rejects a non-GeneratedMessageV3 result")
        void rejectsNonProtoResult() {
            assertThrows(IllegalArgumentException.class,
                    () -> serializer.serializeResult("plain string", TEST_CONFIG));
        }

        @Test
        @DisplayName("deserializeResult rejects a non-GeneratedMessageV3 return type")
        void rejectsNonProtoReturnType() {
            assertThrows(IllegalArgumentException.class,
                    () -> serializer.deserializeResult(new byte[0], String.class, TEST_CONFIG));
        }

        @Test
        @DisplayName("serializeResult/deserializeResult round-trip ItaraErrorPayload via the shared codec (ADR 0020)")
        void errorPayloadRoundTrips() throws Exception {
            ItaraErrorPayload payload = new ItaraErrorPayload(
                    ItaraRemoteException.ErrorKind.CHECKED, "com.example.SomeException", "boom");

            byte[] bytes = serializer.serializeResult(payload, TEST_CONFIG);
            ItaraErrorPayload decoded = (ItaraErrorPayload)
                    serializer.deserializeResult(bytes, ItaraErrorPayload.class, TEST_CONFIG);

            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, decoded.getErrorKind());
            assertEquals("com.example.SomeException", decoded.getRemoteExceptionClass());
            assertEquals("boom", decoded.getMessage());
        }

        @Test
        @DisplayName("error payload path takes precedence even if a GeneratedMessageV3 check would otherwise apply")
        void errorPayloadDoesNotFallThroughToGeneratedMessageCheck() throws Exception {
            ItaraErrorPayload payload = new ItaraErrorPayload(
                    ItaraRemoteException.ErrorKind.TRANSPORT, "com.example.Infra", null);

            // Should not throw IllegalArgumentException about "not a GeneratedMessageV3" —
            // the instanceof ItaraErrorPayload check must be reached first.
            byte[] bytes = serializer.serializeResult(payload, TEST_CONFIG);
            ItaraErrorPayload decoded = (ItaraErrorPayload)
                    serializer.deserializeResult(bytes, ItaraErrorPayload.class, TEST_CONFIG);

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, decoded.getErrorKind());
            assertNull(decoded.getMessage());
        }
    }
}
