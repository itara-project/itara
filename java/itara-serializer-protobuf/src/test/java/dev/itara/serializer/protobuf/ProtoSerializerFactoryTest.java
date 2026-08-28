package dev.itara.serializer.protobuf;

import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.SerializerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("ProtoSerializerFactory")
class ProtoSerializerFactoryTest {

    private final ProtoSerializerFactory factory = new ProtoSerializerFactory();

    @Test
    @DisplayName("id() returns 'protobuf'")
    void id() {
        assertEquals("protobuf", factory.id());
    }

    @Test
    @DisplayName("parseConfig() returns the same constant config regardless of params")
    void parseConfigIsConstant() {
        SerializerConfig withNoParams = SerializerConfig.builder().build();
        SerializerConfig withParams = SerializerConfig.builder()
                .params(Map.of("whatever", "value"))
                .build();

        ItaraSerializerConfig a = factory.parseConfig(withNoParams);
        ItaraSerializerConfig b = factory.parseConfig(withParams);

        assertSame(ProtoSerializerConfig.INSTANCE, a);
        assertSame(ProtoSerializerConfig.INSTANCE, b);
    }

    @Test
    @DisplayName("two parsed configs always share an equal grouping key")
    void groupingKeysAreEqual() {
        ItaraSerializerConfig a = factory.parseConfig(SerializerConfig.builder().build());
        ItaraSerializerConfig b = factory.parseConfig(SerializerConfig.builder().build());

        assertEquals(a.groupingKey(), b.groupingKey());
        assertEquals(a.groupingKey().hashCode(), b.groupingKey().hashCode());
    }

    @Test
    @DisplayName("create() returns a working, independent ProtoItaraSerializer instance each call")
    void createProducesWorkingInstances() {
        ItaraSerializerConfig config = factory.parseConfig(SerializerConfig.builder().build());

        Object first = factory.create(config);
        Object second = factory.create(config);

        assertInstanceOf(ProtoItaraSerializer.class, first);
        assertInstanceOf(ProtoItaraSerializer.class, second);
        assertNotSame(first, second);
    }
}
