package io.itara.serializer.java;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.spi.ItaraSerializer;

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
 * Exception handling:
 *   Consistent with all Itara serializers, exceptions are serialized as a
 *   structured ItaraRemoteException rather than the raw Throwable type.
 *   This ensures identical caller-side behavior regardless of which
 *   serializer is in use. The original exception type is not reconstructed
 *   even though Java serialization would technically allow it — consistency
 *   across serializers is more valuable than type fidelity.
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
    public byte[] serializeArgs(Object[] args) throws Exception {
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
    public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return (Object[]) ois.readObject();
        }
    }

    /**
     * Serializes a return value or exception using Java object serialization.
     *
     * If the result is a Throwable, it is serialized as a structured
     * ItaraRemoteException rather than the raw exception type. This ensures
     * consistent caller-side behavior regardless of which serializer is in use.
     *
     * Normal results are serialized directly. All result types must implement
     * java.io.Serializable.
     */
    @Override
    public byte[] serializeResult(Object result) throws Exception {
        Object toSerialize = (result instanceof Throwable t)
                ? buildRemoteException(t)
                : result;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(toSerialize);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes a return value or ItaraRemoteException from Java
     * object serialization bytes.
     *
     * If the target type is ItaraRemoteException, the deserialized object
     * is cast and returned directly — the serialized payload will already
     * be an ItaraRemoteException as produced by serializeResult.
     *
     * For void methods (Void.TYPE), returns null regardless of payload.
     *
     * Unlike the JSON serializer, returnType is not used to guide
     * deserialization — Java serialization preserves type information
     * natively. It is accepted for SPI interface consistency.
     */
    @Override
    public Object deserializeResult(byte[] bytes, Class<?> returnType) throws Exception {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    // — private helpers —

    private ItaraRemoteException buildRemoteException(Throwable t) {
        ItaraRemoteException.ErrorKind kind =
                (t instanceof RuntimeException || t instanceof Error)
                        ? ItaraRemoteException.ErrorKind.RUNTIME
                        : ItaraRemoteException.ErrorKind.CHECKED;
        return new ItaraRemoteException(kind, t.getClass().getName(), t.getMessage());
    }
}
