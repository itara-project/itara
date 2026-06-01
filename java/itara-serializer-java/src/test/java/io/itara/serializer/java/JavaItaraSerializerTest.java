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
