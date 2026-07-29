package io.itara.serializer.protobuf;

import com.google.protobuf.GeneratedMessageV3;
import io.itara.exceptions.ItaraErrorPayload;
import io.itara.exceptions.ItaraErrorPayloadCodec;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol Buffers serializer implementation (ADR 0019).
 *
 * Proto is adopted purely as a message format — a way of generating the
 * types used as a contract's method parameters and return values — not
 * as a transport or contract mechanism. This class is the serializer-SPI
 * side of that decision: it converts proto-generated message instances
 * to and from bytes generically, via reflection, with no per-message-type
 * hardcoding.
 *
 * Wire format:
 *
 *   Each business payload — a single method argument, or a return value —
 *   is the raw protobuf wire encoding of exactly one GeneratedMessageV3
 *   instance, produced by that instance's own toByteArray(). There is no
 *   enclosing envelope and no length prefix; the transport is responsible
 *   for framing (ADR 0019 — proto is not a transport).
 *
 * Argument count:
 *
 *   A proto-format contract method may declare at most one parameter.
 *   Protobuf has no wire representation for "an array of arbitrary
 *   messages" the way JSON has arrays — each message is its own
 *   self-contained encoding — so unlike the JSON and Java serializers,
 *   which serialize the whole Object[] as a single unit, this serializer
 *   deals with a single message at a time. This mirrors how proto/RPC
 *   APIs are conventionally designed: one request message in, one
 *   response message out. A method declaring two or more parameters is
 *   not supported and serializeArgs()/deserializeArgs() will throw.
 *
 * Outbound (serializeArgs, serializeResult):
 *
 *   toByteArray() is a method on com.google.protobuf.Message, which every
 *   GeneratedMessageV3 implements — this is a direct interface call, not
 *   reflection. Reflection is not needed in the outbound direction at all.
 *
 * Inbound (deserializeArgs, deserializeResult):
 *
 *   Every protobuf-generated message class declares its own static
 *   parseFrom(byte[]) factory method. There is no common interface for
 *   this — it is generated per class, not inherited — so resolving it
 *   requires reflection on the target type (the declared parameter type
 *   for arguments, the declared return type for results). The resolved
 *   Method is cached per class to avoid repeating the lookup on every call.
 *
 * Error payload handling (ADR 0020):
 *
 *   ItaraErrorPayload is not a GeneratedMessageV3 — the generic
 *   toByteArray()/parseFrom() path above has nothing to do with it — so
 *   this serializer special-cases it explicitly in both directions and
 *   delegates to {@link ItaraErrorPayloadCodec}, the shared,
 *   dependency-free wire format every non-generic serializer reuses
 *   rather than each inventing its own encoding. This satisfies the
 *   unconditional baseline obligation every serializer has regardless of
 *   its message-format specialization.
 */
public class ProtoItaraSerializer implements ItaraSerializer {

    private static final Map<Class<?>, Method> PARSE_FROM_CACHE = new ConcurrentHashMap<>();

    ProtoItaraSerializer() {
    }

    @Override
    public String type() {
        return "protobuf";
    }

    /**
     * Serializes a single method argument via its own toByteArray().
     *
     * A method declaring no parameters produces an empty byte array. A
     * method declaring exactly one parameter serializes that argument
     * directly — no envelope. A method declaring two or more parameters
     * is not supported (see class javadoc) and this method throws.
     *
     * The config parameter is unused — this serializer has no
     * per-connection configuration (see ProtoSerializerConfig).
     */
    @Override
    public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) throws Exception {
        if (args == null || args.length == 0) {
            return new byte[0];
        }
        requireAtMostOneArgument(args.length);

        Object arg = args[0];
        if (!(arg instanceof GeneratedMessageV3)) {
            throw new IllegalArgumentException(
                    "[Itara] Proto serializer requires a GeneratedMessageV3 argument; got "
                            + (arg != null ? arg.getClass().getName() : "null"));
        }
        return ((GeneratedMessageV3) arg).toByteArray();
    }

    /**
     * Deserializes a single method argument via reflection on the
     * declared parameter type.
     *
     * A method declaring no parameters returns an empty array regardless
     * of the bytes given. A method declaring exactly one parameter
     * resolves that parameter's type's static parseFrom(byte[]) and
     * invokes it. A method declaring two or more parameters is not
     * supported (see class javadoc) and this method throws.
     *
     * The config parameter is unused — this serializer has no
     * per-connection configuration (see ProtoSerializerConfig).
     */
    @Override
    public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) throws Exception {
        if (paramTypes == null || paramTypes.length == 0) {
            return new Object[0];
        }
        requireAtMostOneArgument(paramTypes.length);

        return new Object[]{ parseGeneratedMessage(paramTypes[0], bytes) };
    }

    /**
     * Serializes a return value via its own toByteArray().
     *
     * Null (void method) serializes as an empty byte array.
     *
     * ItaraErrorPayload is special-cased and delegated to
     * {@link ItaraErrorPayloadCodec} — see the class javadoc's "Error
     * payload handling" section.
     *
     * The config parameter is unused — this serializer has no
     * per-connection configuration (see ProtoSerializerConfig).
     */
    @Override
    public byte[] serializeResult(Object result, ItaraSerializerConfig config) throws Exception {
        if (result == null) {
            return new byte[0];
        }
        if (result instanceof ItaraErrorPayload) {
            return ItaraErrorPayloadCodec.encode((ItaraErrorPayload) result);
        }
        if (!(result instanceof GeneratedMessageV3)) {
            throw new IllegalArgumentException(
                    "[Itara] Proto serializer requires a GeneratedMessageV3 return value; got "
                            + result.getClass().getName());
        }
        return ((GeneratedMessageV3) result).toByteArray();
    }

    /**
     * Deserializes a return value via reflection on the declared return type.
     *
     * For void methods (Void.TYPE), returns null regardless of payload.
     *
     * ItaraErrorPayload.class as the target type is special-cased and
     * delegated to {@link ItaraErrorPayloadCodec} — see the class
     * javadoc's "Error payload handling" section.
     *
     * The config parameter is unused — this serializer has no
     * per-connection configuration (see ProtoSerializerConfig).
     */
    @Override
    public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) throws Exception {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        if (returnType == ItaraErrorPayload.class) {
            return ItaraErrorPayloadCodec.decode(bytes);
        }
        return parseGeneratedMessage(returnType, bytes);
    }

    private static void requireAtMostOneArgument(int count) {
        if (count > 1) {
            throw new IllegalArgumentException(
                    "[Itara] Proto serializer supports at most one method argument; got " + count
                            + ". Proto-format contracts must declare a single request-message parameter"
                            + " per method (see ProtoItaraSerializer class javadoc).");
        }
    }

    private static Object parseGeneratedMessage(Class<?> targetClass, byte[] bytes) throws Exception {
        if (!GeneratedMessageV3.class.isAssignableFrom(targetClass)) {
            throw new IllegalArgumentException(
                    "[Itara] " + targetClass.getName() + " is not a protobuf GeneratedMessageV3 type.");
        }

        Method parseFrom = PARSE_FROM_CACHE.computeIfAbsent(targetClass, ProtoItaraSerializer::lookupParseFrom);

        try {
            return parseFrom.invoke(null, (Object) bytes);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static Method lookupParseFrom(Class<?> targetClass) {
        try {
            return targetClass.getMethod("parseFrom", byte[].class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "[Itara] " + targetClass.getName()
                            + " does not declare a public static parseFrom(byte[]) method — "
                            + "is it a valid protobuf-generated message class?", e);
        }
    }
}
