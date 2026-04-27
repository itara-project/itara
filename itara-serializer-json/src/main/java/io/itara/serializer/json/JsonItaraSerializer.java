package io.itara.serializer.json;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.spi.ItaraSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON serializer implementation using Jackson.
 *
 * Wire format:
 *
 *   Arguments are serialized as a JSON array, one element per argument,
 *   in declaration order. This format is intentionally human-readable and
 *   curl-friendly — a POST with a JSON array body is a valid Itara call.
 *
 *   Example — add(3, 4):
 *     [3, 4]
 *
 *   Return values are serialized as a single JSON value or object:
 *     7
 *     {"id": 1, "name": "example"}
 *
 *   Error responses are serialized as a structured JSON object:
 *     {
 *       "errorKind": "CHECKED",
 *       "remoteExceptionClass": "com.example.ValidationException",
 *       "message": "amount must be positive"
 *     }
 *
 * Timestamp handling:
 *   java.time types (Instant, LocalDateTime, ZonedDateTime, etc.) are
 *   serialized as ISO 8601 strings for human readability and broad tooling
 *   compatibility. Example: "2026-04-25T14:32:00.123456789Z"
 *   Nanosecond precision is preserved.
 *
 * Map handling:
 *   Maps with known value types are deserialized exactly. Maps with Object
 *   values (Map<String, Object>) will deserialize JSON objects as
 *   LinkedHashMap, arrays as ArrayList, and primitives as their natural
 *   Java equivalents. This is a Jackson limitation for unparameterized types.
 *
 * Overloaded methods:
 *   Not supported. If a component interface declares overloaded methods,
 *   deserialization may select the wrong method signature. Document as a
 *   known limitation — see transport layer tech debt note.
 */
public class JsonItaraSerializer implements ItaraSerializer {

    private static final String ERROR_KIND_FIELD       = "errorKind";
    private static final String ERROR_CLASS_FIELD      = "remoteExceptionClass";
    private static final String ERROR_MESSAGE_FIELD    = "message";

    private final ObjectMapper mapper;

    public JsonItaraSerializer() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Override
    public String type() {
        return "json";
    }

    /**
     * Serializes method arguments as a JSON array.
     *
     * Null arguments are preserved as JSON null. An empty argument list
     * produces an empty array [].
     */
    @Override
    public byte[] serializeArgs(Object[] args) throws Exception {
        return mapper.writeValueAsBytes(args);
    }

    /**
     * Deserializes a JSON array into a typed argument array.
     *
     * Each element is deserialized using the declared parameter type from
     * the contract method signature. This is necessary because JSON loses
     * type information — without the target type, Jackson cannot distinguish
     * a Long from an Integer, or a custom object from a Map.
     */
    @Override
    public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes) throws Exception {
        Object[] rawArgs = mapper.readValue(bytes, Object[].class);
        Object[] typedArgs = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            // Convert each raw value to the declared parameter type.
            // convertValue handles primitive widening, Map-to-POJO conversion,
            // and java.time deserialization correctly.
            typedArgs[i] = mapper.convertValue(rawArgs[i], paramTypes[i]);
        }
        return typedArgs;
    }

    /**
     * Serializes a return value or a Throwable as JSON.
     *
     * If the result is a Throwable, it is serialized as a structured error
     * object containing the error kind, the original exception class name,
     * and the message. The error kind is inferred from the exception type:
     * RuntimeException and Error are RUNTIME, all others are CHECKED.
     *
     * Normal results are serialized as their natural JSON representation.
     * Null (void method) serializes as JSON null.
     */
    @Override
    public byte[] serializeResult(Object result) throws Exception {
        if (result instanceof Throwable t) {
            return serializeThrowable(t);
        }
        return mapper.writeValueAsBytes(result);
    }

    /**
     * Deserializes a return value or an ItaraRemoteException from JSON.
     *
     * If the target type is ItaraRemoteException, the payload is treated
     * as a structured error object and reconstructed accordingly.
     * Otherwise the payload is deserialized as the declared return type.
     *
     * For void methods (Void.TYPE), returns null regardless of payload.
     */
    @Override
    public Object deserializeResult(byte[] bytes, Class<?> returnType) throws Exception {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        if (returnType == ItaraRemoteException.class) {
            return deserializeError(bytes);
        }
        JavaType javaType = mapper.getTypeFactory().constructType(returnType);
        return mapper.readValue(bytes, javaType);
    }

    // — private helpers —

    private byte[] serializeThrowable(Throwable t) throws Exception {
        ItaraRemoteException.ErrorKind kind =
                (t instanceof RuntimeException || t instanceof Error)
                        ? ItaraRemoteException.ErrorKind.RUNTIME
                        : ItaraRemoteException.ErrorKind.CHECKED;

        Map<String, String> error = new LinkedHashMap<>();
        error.put(ERROR_KIND_FIELD,    kind.name());
        error.put(ERROR_CLASS_FIELD,   t.getClass().getName());
        error.put(ERROR_MESSAGE_FIELD, t.getMessage());
        return mapper.writeValueAsBytes(error);
    }

    private ItaraRemoteException deserializeError(byte[] bytes) throws Exception {
        Map<?, ?> error = mapper.readValue(bytes, Map.class);

        String kindStr   = (String) error.get(ERROR_KIND_FIELD);
        String className = (String) error.get(ERROR_CLASS_FIELD);
        String message   = (String) error.get(ERROR_MESSAGE_FIELD);

        ItaraRemoteException.ErrorKind kind;
        try {
            kind = ItaraRemoteException.ErrorKind.valueOf(kindStr);
        } catch (IllegalArgumentException e) {
            // Unrecognized error kind — treat as transport failure rather
            // than silently swallowing the error or crashing deserialization
            kind = ItaraRemoteException.ErrorKind.TRANSPORT;
        }

        return new ItaraRemoteException(kind, className, message);
    }
}
