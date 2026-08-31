package dev.itara.serializer.java;

import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;

import java.io.*;

/**
 * Legacy Java object serialization implementation of ItaraSerializer.
 *
 * Uses Java's built-in ObjectOutputStream / ObjectInputStream for
 * serialization. No external dependencies.
 *
 * This serializer is provided for backward compatibility with systems
 * that were built against the original Itara PoC transport layer.
 * It is explicitly opt-in — it is never the default for any connection.
 *
 * Known limitations:
 *
 *   JVM-only — the wire format is Java object serialization. Non-JVM
 *   components cannot participate in connections using this serializer.
 *
 *   Version-sensitive — serialized objects are sensitive to class changes.
 *   Adding, removing, or reordering fields between deployments can cause
 *   deserialization failures unless serialVersionUID is managed carefully.
 *
 *   Security — Java deserialization is a historical source of remote code
 *   execution vulnerabilities. Only use this serializer in trusted,
 *   internal networks where all callers are known and controlled.
 *
 *   Not curl-friendly — the wire format is opaque binary. Endpoints using
 *   this serializer cannot be tested with curl or any standard HTTP tooling.
 *
 *   Serializable requirement — all argument types, return types, and
 *   ItaraErrorPayload must implement java.io.Serializable. The caller is
 *   responsible for ensuring this. The serializer does not validate it.
 *
 * Configuration:
 *   serializer: java   # in wiring config — must be explicit, never default
 */
public class JavaItaraSerializer implements ItaraSerializer {
    @Override
    public String type() {
        return "java";
    }

    /**
     * Serializes method arguments using Java object serialization.
     *
     * The entire Object[] is written as a single serialized object.
     * Null arguments are preserved. An empty argument list produces
     * a serialized empty array.
     *
     * All argument types must implement java.io.Serializable.
     */
    @Override
    public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(args);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes method arguments from Java object serialization bytes.
     *
     * Unlike the JSON serializer, Java serialization preserves type
     * information natively — paramTypes is not used for deserialization
     * but is accepted for SPI interface consistency.
     *
     * The deserialized Object[] is returned directly. Types are preserved
     * exactly as serialized by the caller.
     */
    @Override
    public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return (Object[]) ois.readObject();
        }
    }

    /**
     * Serializes a return value using Java object serialization.
     *
     * The caller is responsible for passing the correct object — error
     * payloads arrive as ItaraErrorPayload, not as Throwables. All types
     * passed here must implement java.io.Serializable.
     */
    @Override
    public byte[] serializeResult(Object result, ItaraSerializerConfig config) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(result);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes a return value from Java object serialization bytes.
     *
     * Java serialization preserves type information natively — returnType
     * is not used to guide deserialization. It is accepted for SPI interface
     * consistency.
     *
     * For void methods (Void.TYPE), returns null regardless of payload.
     */
    @Override
    public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) throws Exception {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
