package io.itara.serializer.json;

import io.itara.exceptions.ItaraRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonItaraSerializer")
public class JsonItaraSerializerTest {

    private JsonItaraSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new JsonItaraSerializer();
    }

    @Test
    @DisplayName("type() returns 'json'")
    void type() {
        assertEquals("json", serializer.type());
    }

    @Nested
    @DisplayName("serializeArgs / deserializeArgs")
    class Args {

        @Test
        @DisplayName("roundtrips primitive args")
        void primitives() throws Exception {
            Object[] args = {3, 4};
            Class<?>[] types = {int.class, int.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            assertArrayEquals(new Object[]{3, 4}, result);
        }

        @Test
        @DisplayName("roundtrips String args")
        void strings() throws Exception {
            Object[] args = {"hello", "world"};
            Class<?>[] types = {String.class, String.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            assertArrayEquals(args, result);
        }

        @Test
        @DisplayName("roundtrips Instant with nanosecond precision")
        void instant() throws Exception {
            Instant now = Instant.now();
            Object[] args = {now};
            Class<?>[] types = {Instant.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            assertEquals(now, result[0]);
        }

        @Test
        @DisplayName("roundtrips Map<String, Object>")
        void map() throws Exception {
            Map<String, Object> map = Map.of("key", "value", "count", 42);
            Object[] args = {map};
            Class<?>[] types = {Map.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            @SuppressWarnings("unchecked")
            Map<String, Object> deserialized = (Map<String, Object>) result[0];
            assertEquals("value", deserialized.get("key"));
            // Jackson deserializes integers as Integer for Map<String, Object>
            assertEquals(42, ((Number) deserialized.get("count")).intValue());
        }

        @Test
        @DisplayName("roundtrips empty args array")
        void empty() throws Exception {
            Object[] args = {};
            Class<?>[] types = {};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            assertArrayEquals(new Object[]{}, result);
        }

        @Test
        @DisplayName("roundtrips null argument")
        void nullArg() throws Exception {
            Object[] args = {null};
            Class<?>[] types = {String.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            assertNull(result[0]);
        }

        @Test
        @DisplayName("produces human-readable JSON array")
        void humanReadable() throws Exception {
            Object[] args = {3, 4};
            byte[] bytes = serializer.serializeArgs(args);
            String json = new String(bytes);
            assertEquals("[3,4]", json);
        }
    }

    @Nested
    @DisplayName("serializeResult / deserializeResult")
    class Result {

        @Test
        @DisplayName("roundtrips a normal return value")
        void normalResult() throws Exception {
            byte[] bytes = serializer.serializeResult(42);
            Object result = serializer.deserializeResult(bytes, int.class);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("roundtrips a String return value")
        void stringResult() throws Exception {
            byte[] bytes = serializer.serializeResult("hello");
            Object result = serializer.deserializeResult(bytes, String.class);
            assertEquals("hello", result);
        }

        @Test
        @DisplayName("roundtrips an Instant return value")
        void instantResult() throws Exception {
            Instant now = Instant.now();
            byte[] bytes = serializer.serializeResult(now);
            Object result = serializer.deserializeResult(bytes, Instant.class);
            assertEquals(now, result);
        }

        @Test
        @DisplayName("returns null for void return type")
        void voidReturn() throws Exception {
            byte[] bytes = serializer.serializeResult(null);
            Object result = serializer.deserializeResult(bytes, Void.TYPE);
            assertNull(result);
        }

        @Test
        @DisplayName("serializes checked exception with CHECKED kind")
        void checkedExceptionKind() throws Exception {
            Exception checked = new Exception("validation failed");
            byte[] bytes = serializer.serializeResult(checked);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, remote.getErrorKind());
            assertEquals("java.lang.Exception", remote.getRemoteExceptionClass());
            assertEquals("validation failed", remote.getMessage());
        }

        @Test
        @DisplayName("serializes RuntimeException with RUNTIME kind")
        void runtimeExceptionKind() throws Exception {
            RuntimeException runtime = new RuntimeException("something exploded");
            byte[] bytes = serializer.serializeResult(runtime);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, remote.getErrorKind());
            assertEquals("java.lang.RuntimeException", remote.getRemoteExceptionClass());
            assertEquals("something exploded", remote.getMessage());
        }

        @Test
        @DisplayName("serializes Error with RUNTIME kind")
        void errorKind() throws Exception {
            Error error = new OutOfMemoryError("heap space");
            byte[] bytes = serializer.serializeResult(error);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, remote.getErrorKind());
        }

        @Test
        @DisplayName("unknown ErrorKind in payload falls back to TRANSPORT")
        void unknownErrorKind() throws Exception {
            // Simulate a payload from a newer server with an unknown error kind
            byte[] payload = ("{\"errorKind\":\"UNKNOWN_FUTURE_KIND\","
                    + "\"remoteExceptionClass\":\"com.example.Ex\","
                    + "\"message\":\"something\"}").getBytes();
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(payload, ItaraRemoteException.class);
            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, remote.getErrorKind());
        }

        @Test
        @DisplayName("error payload preserves original exception class name")
        void preservesExceptionClassName() throws Exception {
            IllegalArgumentException ex = new IllegalArgumentException("bad input");
            byte[] bytes = serializer.serializeResult(ex);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals("java.lang.IllegalArgumentException",
                    remote.getRemoteExceptionClass());
        }
    }
}
