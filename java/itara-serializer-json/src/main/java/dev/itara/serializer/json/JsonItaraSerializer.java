package dev.itara.serializer.json;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;

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
 *   Error responses are serialized as ItaraErrorPayload — a plain DTO
 *   handled by the caller before reaching the serializer. The serializer
 *   sees it as any other object.
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

    private final ObjectMapper mapper;

    JsonItaraSerializer(JsonSerializerConfig config) {
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
     *
     * The config parameter is currently unused by this method — the
     * Jackson mapper's behaviour does not yet vary per connection —
     * though it does carry each connection's raw serializer params
     * (see JsonSerializerConfig) for whenever a real configurable
     * option is added here.
     */
    @Override
    public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) throws Exception {
        return mapper.writeValueAsBytes(args);
    }

    /**
     * Deserializes a JSON array into a typed argument array.
     *
     * Each element is deserialized using the declared parameter type from
     * the contract method signature. This is necessary because JSON loses
     * type information — without the target type, Jackson cannot distinguish
     * a Long from an Integer, or a custom object from a Map.
     *
     * The config parameter is currently unused by this method — the
     * Jackson mapper's behaviour does not yet vary per connection —
     * though it does carry each connection's raw serializer params
     * (see JsonSerializerConfig) for whenever a real configurable
     * option is added here.
     */
    @Override
    public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) throws Exception {
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
     * The caller is responsible for preparing the correct object before
     * serialization — error payloads arrive as ItaraErrorPayload, not as
     * Throwables. Null (void method) serializes as JSON null.
     *
     * This same generic path handles ItaraErrorPayload correctly — Jackson
     * serializes it as an ordinary POJO, so no special-casing is needed
     * to satisfy the unconditional error-payload obligation (ADR 0020).
     *
     * The config parameter is currently unused by this method — the
     * Jackson mapper's behaviour does not yet vary per connection —
     * though it does carry each connection's raw serializer params
     * (see JsonSerializerConfig) for whenever a real configurable
     * option is added here.
     */
    @Override
    public byte[] serializeResult(Object result, ItaraSerializerConfig config) throws Exception {
        return mapper.writeValueAsBytes(result);
    }

    /**
     * Deserializes a return value from JSON using the declared return type.
     *
     * For void methods (Void.TYPE), returns null regardless of payload.
     * This same generic path is also used to deserialize ItaraErrorPayload
     * (see serializeResult) — no special-casing needed.
     *
     * The config parameter is currently unused by this method — the
     * Jackson mapper's behaviour does not yet vary per connection —
     * though it does carry each connection's raw serializer params
     * (see JsonSerializerConfig) for whenever a real configurable
     * option is added here.
     */
    @Override
    public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) throws Exception {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        JavaType javaType = mapper.getTypeFactory().constructType(returnType);
        return mapper.readValue(bytes, javaType);
    }
}
