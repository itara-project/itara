package dev.itara.spi.serializer;

/**
 * Service Provider Interface for Itara serializers.
 *
 * <p>A serializer is responsible for converting method arguments and return
 * values to and from byte arrays for transport across process or network
 * boundaries. It is transport-agnostic — the same serializer implementation
 * works with any transport that carries bytes.
 *
 * <p>Implementations live in separate jars (itara-serializer-json,
 * itara-serializer-java, etc.) and are discovered by the agent at startup
 * via META-INF/itara/serializer on the classpath. Each descriptor entry
 * names an {@link ItaraSerializerFactory} implementation, not this interface
 * directly — the factory is what the agent instantiates, and it in turn
 * produces instances of this interface via {@link ItaraSerializerFactory#create}.
 *
 * <p>The serializer type string (e.g. "json", "java") must match the id field
 * in the connection's serializer block in the wiring config.
 *
 * <p>A serializer is resolved once per connection at startup and held as a
 * final field in the proxy and dispatcher. It is never looked up at call
 * time — topology decisions, including serialization format, are startup
 * concerns, not runtime concerns.
 *
 * <p>Multiple serializer implementations may be active simultaneously if
 * different connections declare different serializer types. A single
 * instance may also serve multiple connections at once when their configs
 * share the same grouping key (see {@link ItaraSerializerFactory}).
 *
 * <p>Every implementation must additionally be capable of serializing and
 * deserializing the fixed error payload structure ({@code ItaraErrorPayload}),
 * unconditionally — this is a baseline obligation independent of whatever
 * business-payload strategy the implementation otherwise uses (see ADR 0020).
 * A serializer whose own strategy is already generic over arbitrary objects
 * (JSON, Java object serialization) typically satisfies this for free. One
 * built around a structural message format that can't represent an
 * arbitrary DTO — protobuf, for instance — can instead reuse {@link
 * dev.itara.exceptions.ItaraErrorPayloadCodec#encode} and {@link
 * dev.itara.exceptions.ItaraErrorPayloadCodec#decode} rather than inventing
 * its own encoding for this one case.
 */
public interface ItaraSerializer {

    /**
     * The serializer type string this implementation handles.
     * Must match the id field within the connection's serializer block
     * in the wiring config.
     * Examples: "json", "java"
     */
    String type();

    /**
     * Serialize method arguments for transport to the remote component.
     *
     * <p>Called by the proxy on the caller side before dispatching the call.
     * The args array corresponds directly to the method parameter list —
     * order and length are preserved.
     *
     * @param args   The method arguments to serialize. May be empty but
     *               never null. Individual elements may be null if the
     *               method parameter type permits it.
     * @param config The parsed, connection-specific serializer config —
     *               the same instance is shared by every call on this
     *               connection, and by every other connection with an
     *               equal grouping key.
     * @return       A byte array representing the serialized arguments
     * @throws Exception if serialization fails for any argument
     */
    byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) throws Exception;

    /**
     * Deserialize method arguments on the receiving side.
     *
     * <p>Called by the listener on the callee side before dispatching to
     * the component implementation. The paramTypes array provides the
     * declared parameter types from the contract interface method
     * signature, available via reflection. Implementations should use
     * these types to guide deserialization — JSON in particular loses
     * type information at the byte level and requires them for correct
     * reconstruction.
     *
     * @param bytes       The serialized argument bytes produced by serializeArgs
     * @param paramTypes  The declared parameter types of the contract method,
     *                    in the same order as the original args array
     * @param config      The parsed, connection-specific serializer config —
     *                    the same instance is shared by every call on this
     *                    connection, and by every other connection with an
     *                    equal grouping key.
     * @return            The reconstructed argument array, ready for method
     *                    invocation. Must match paramTypes in length and
     *                    assignability.
     * @throws Exception  if deserialization fails for any argument
     */
    Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) throws Exception;

    /**
     * Serialize a return value for transport back to the caller.
     *
     * <p>Called by the listener on the callee side after the component
     * implementation returns. The transport is responsible for signaling
     * success or failure via its own mechanism (HTTP status code, message
     * header, etc.) — this method serializes data only.
     *
     * @param result  The return value to serialize. May be null for void
     *                methods that completed normally.
     * @param config  The parsed, connection-specific serializer config —
     *                the same instance is shared by every call on this
     *                connection, and by every other connection with an
     *                equal grouping key.
     * @return        A byte array representing the serialized result
     * @throws Exception if serialization fails
     */
    byte[] serializeResult(Object result, ItaraSerializerConfig config) throws Exception;

    /**
     * Deserialize a return value on the caller side.
     *
     * <p>Called by the transport proxy after receiving a response. The transport
     * determines the expected type — return type on success, exception class
     * on error — via its own signaling mechanism and passes it here.
     * This method deserializes whatever type it is given.
     *
     * @param bytes       The serialized bytes produced by serializeResult
     * @param returnType  The type to deserialize into. The declared return
     *                    type of the contract method on success, an exception
     *                    class on error. Void.TYPE for void methods — return null.
     * @param config      The parsed, connection-specific serializer config —
     *                    the same instance is shared by every call on this
     *                    connection, and by every other connection with an
     *                    equal grouping key.
     * @return            The reconstructed object, assignable to returnType,
     *                    or null for void methods
     * @throws Exception  if deserialization fails
     */
    Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) throws Exception;
}
