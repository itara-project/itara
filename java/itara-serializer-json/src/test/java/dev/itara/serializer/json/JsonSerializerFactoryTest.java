package dev.itara.serializer.json;

import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.SerializerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@DisplayName("JsonSerializerFactory")
class JsonSerializerFactoryTest {

    private final JsonSerializerFactory factory = new JsonSerializerFactory();

    @Test
    @DisplayName("id() returns 'json'")
    void id() {
        assertEquals("json", factory.id());
    }

    @Test
    @DisplayName("two connections with equal params (including both empty) share an equal grouping key")
    void equalParamsShareGroupingKey() throws Exception {
        ItaraSerializerConfig a = factory.parseConfig(SerializerConfig.builder().build());
        ItaraSerializerConfig b = factory.parseConfig(SerializerConfig.builder().build());

        assertEquals(a.groupingKey(), b.groupingKey());
        assertEquals(a.groupingKey().hashCode(), b.groupingKey().hashCode());

        ItaraSerializerConfig c = factory.parseConfig(
                SerializerConfig.builder().params(Map.of("indent", "true")).build());
        ItaraSerializerConfig d = factory.parseConfig(
                SerializerConfig.builder().params(Map.of("indent", "true")).build());

        assertEquals(c.groupingKey(), d.groupingKey());
    }

    @Test
    @DisplayName("two connections with different params get different grouping keys")
    void differentParamsGetDifferentGroupingKey() throws Exception {
        ItaraSerializerConfig withoutParams = factory.parseConfig(SerializerConfig.builder().build());
        ItaraSerializerConfig withParams = factory.parseConfig(
                SerializerConfig.builder().params(Map.of("indent", "true")).build());

        assertNotEquals(withoutParams.groupingKey(), withParams.groupingKey());
    }

    @Test
    @DisplayName("create() returns a working, independent JsonItaraSerializer instance each call")
    void createProducesWorkingInstances() throws Exception {
        ItaraSerializerConfig config = factory.parseConfig(SerializerConfig.builder().build());

        Object first = factory.create(config);
        Object second = factory.create(config);

        assertInstanceOf(JsonItaraSerializer.class, first);
        assertInstanceOf(JsonItaraSerializer.class, second);
        // The factory does not cache — the registry owns instance sharing
        // via the grouping key. Two calls produce two distinct instances.
        assertNotSame(first, second);
    }
}
