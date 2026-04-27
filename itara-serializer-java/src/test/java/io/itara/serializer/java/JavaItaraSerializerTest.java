package io.itara.serializer.java;

import io.itara.exceptions.ItaraRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaItaraSerializer")
public class JavaItaraSerializerTest {

    private JavaItaraSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new JavaItaraSerializer();
    }

    @Test
    @DisplayName("type() returns 'java'")
    void type() {
        assertEquals("java", serializer.type());
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
        @DisplayName("roundtrips serializable custom object")
        void customObject() throws Exception {
            SerializablePoint point = new SerializablePoint(3, 7);
            Object[] args = {point};
            Class<?>[] types = {SerializablePoint.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), types);
            SerializablePoint deserialized = (SerializablePoint) result[0];
            assertEquals(3, deserialized.x);
            assertEquals(7, deserialized.y);
        }

        @Test
        @DisplayName("paramTypes are not required for deserialization — types preserved natively")
        void paramTypesIgnored() throws Exception {
            // Java serialization preserves types — passing wrong paramTypes
            // should still produce the correct result
            Object[] args = {42, "hello"};
            Class<?>[] wrongTypes = {String.class, Integer.class};
            Object[] result = serializer.deserializeArgs(
                    serializer.serializeArgs(args), wrongTypes);
            assertEquals(42, result[0]);
            assertEquals("hello", result[1]);
        }
    }

    @Nested
    @DisplayName("serializeResult / deserializeResult")
    class Result {

        @Test
        @DisplayName("roundtrips a normal integer return value")
        void intResult() throws Exception {
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
        @DisplayName("roundtrips a serializable custom object")
        void customObjectResult() throws Exception {
            SerializablePoint point = new SerializablePoint(5, 10);
            byte[] bytes = serializer.serializeResult(point);
            SerializablePoint result = (SerializablePoint)
                    serializer.deserializeResult(bytes, SerializablePoint.class);
            assertEquals(5, result.x);
            assertEquals(10, result.y);
        }

        @Test
        @DisplayName("returns null for Void.TYPE regardless of payload")
        void voidReturn() throws Exception {
            byte[] bytes = serializer.serializeResult(null);
            Object result = serializer.deserializeResult(bytes, Void.TYPE);
            assertNull(result);
        }

        @Test
        @DisplayName("serializes checked exception as ItaraRemoteException with CHECKED kind")
        void checkedExceptionKind() throws Exception {
            Exception checked = new Exception("validation failed");
            byte[] bytes = serializer.serializeResult(checked);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertAll(
                    () -> assertEquals(ItaraRemoteException.ErrorKind.CHECKED,
                            remote.getErrorKind()),
                    () -> assertEquals("java.lang.Exception",
                            remote.getRemoteExceptionClass()),
                    () -> assertEquals("validation failed", remote.getMessage())
            );
        }

        @Test
        @DisplayName("serializes RuntimeException as ItaraRemoteException with RUNTIME kind")
        void runtimeExceptionKind() throws Exception {
            RuntimeException runtime = new RuntimeException("something exploded");
            byte[] bytes = serializer.serializeResult(runtime);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertAll(
                    () -> assertEquals(ItaraRemoteException.ErrorKind.RUNTIME,
                            remote.getErrorKind()),
                    () -> assertEquals("java.lang.RuntimeException",
                            remote.getRemoteExceptionClass()),
                    () -> assertEquals("something exploded", remote.getMessage())
            );
        }

        @Test
        @DisplayName("serializes Error as ItaraRemoteException with RUNTIME kind")
        void errorKind() throws Exception {
            Error error = new OutOfMemoryError("heap space");
            byte[] bytes = serializer.serializeResult(error);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, remote.getErrorKind());
        }

        @Test
        @DisplayName("raw Throwable is never serialized — always wrapped in ItaraRemoteException")
        void rawThrowableNotSerialized() throws Exception {
            IllegalArgumentException ex = new IllegalArgumentException("bad input");
            byte[] bytes = serializer.serializeResult(ex);
            Object deserialized = serializer.deserializeResult(
                    bytes, ItaraRemoteException.class);
            // Must be ItaraRemoteException, not IllegalArgumentException
            assertInstanceOf(ItaraRemoteException.class, deserialized);
            assertFalse(deserialized instanceof IllegalArgumentException);
        }

        @Test
        @DisplayName("exception class name is preserved")
        void exceptionClassNamePreserved() throws Exception {
            IllegalStateException ex = new IllegalStateException("bad state");
            byte[] bytes = serializer.serializeResult(ex);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals("java.lang.IllegalStateException",
                    remote.getRemoteExceptionClass());
        }

        @Test
        @DisplayName("exception message is preserved")
        void exceptionMessagePreserved() throws Exception {
            Exception ex = new Exception("original message");
            byte[] bytes = serializer.serializeResult(ex);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            assertEquals("original message", remote.getMessage());
        }
    }

    @Nested
    @DisplayName("consistency with JsonItaraSerializer")
    class Consistency {

        @Test
        @DisplayName("checked exception produces same ErrorKind as JSON serializer")
        void checkedExceptionConsistency() throws Exception {
            Exception checked = new Exception("checked");
            byte[] bytes = serializer.serializeResult(checked);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            // Must match JSON serializer behavior — CHECKED for non-runtime exceptions
            assertEquals(ItaraRemoteException.ErrorKind.CHECKED, remote.getErrorKind());
        }

        @Test
        @DisplayName("runtime exception produces same ErrorKind as JSON serializer")
        void runtimeExceptionConsistency() throws Exception {
            RuntimeException runtime = new RuntimeException("runtime");
            byte[] bytes = serializer.serializeResult(runtime);
            ItaraRemoteException remote = (ItaraRemoteException)
                    serializer.deserializeResult(bytes, ItaraRemoteException.class);
            // Must match JSON serializer behavior — RUNTIME for RuntimeException
            assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, remote.getErrorKind());
        }
    }

    // — test fixtures —

    static class SerializablePoint implements Serializable {
        final int x;
        final int y;

        SerializablePoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
