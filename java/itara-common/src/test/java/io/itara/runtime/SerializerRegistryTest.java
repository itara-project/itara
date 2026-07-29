package io.itara.runtime;

import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerFactory;
import io.itara.spi.serializer.ItaraSerializerGroupingKey;
import io.itara.spi.serializer.SerializerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SerializerRegistry")
public class SerializerRegistryTest {

    @BeforeEach
    void reset() {
        SerializerRegistry.instance().reset();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    static class TestGroupingKey implements ItaraSerializerGroupingKey {
        private final String value;
        TestGroupingKey(String value) { this.value = value; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TestGroupingKey)) return false;
            return Objects.equals(value, ((TestGroupingKey) o).value);
        }
        @Override public int hashCode() { return Objects.hash(value); }
        @Override public String toString() { return "TestGroupingKey(" + value + ")"; }
    }

    static class TestSerializerConfig implements ItaraSerializerConfig {
        private final String keyValue;
        private final String extraField;

        TestSerializerConfig(String keyValue, String extraField) {
            this.keyValue   = keyValue;
            this.extraField = extraField;
        }

        @Override
        public ItaraSerializerGroupingKey groupingKey() {
            return new TestGroupingKey(keyValue);
        }
    }

    static class TestSerializer implements ItaraSerializer {
        static int instanceCount = 0;
        final int instanceId;

        TestSerializer() { instanceId = ++instanceCount; }

        @Override
        public String type() { return "test"; }

        @Override
        public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) { return new byte[0]; }

        @Override
        public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) {
            return new Object[paramTypes.length];
        }

        @Override
        public byte[] serializeResult(Object result, ItaraSerializerConfig config) { return new byte[0]; }

        @Override
        public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) {
            return null;
        }
    }

    static class TestSerializerFactory implements ItaraSerializerFactory {
        final String serializerId;
        int parseCallCount  = 0;
        int createCallCount = 0;

        TestSerializerFactory(String serializerId) {
            this.serializerId = serializerId;
        }

        @Override
        public String id() { return serializerId; }

        @Override
        public ItaraSerializerConfig parseConfig(SerializerConfig config) {
            parseCallCount++;
            String key = config.getParams().getOrDefault("key", "default");
            String extra = config.getParams().getOrDefault("extra", "");
            return new TestSerializerConfig(key, extra);
        }

        @Override
        public ItaraSerializer create(ItaraSerializerConfig config) {
            createCallCount++;
            return new TestSerializer();
        }
    }

    private static SerializerConfig rawConfig(String key) {
        return SerializerConfig.builder()
                .params(Map.of("key", key))
                .build();
    }

    private static SerializerConfig rawConfigWithExtra(String key, String extra) {
        return SerializerConfig.builder()
                .params(Map.of("key", key, "extra", extra))
                .build();
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("factory registration")
    class FactoryRegistration {

        @Test
        @DisplayName("registered factory is used by parseConfig")
        void registeredFactoryUsedByParseConfig() throws Exception {
            TestSerializerFactory factory = new TestSerializerFactory("test");
            SerializerRegistry.instance().registerFactory(factory);

            SerializerRegistry.instance().parseConfig("test", rawConfig("k1"));

            assertEquals(1, factory.parseCallCount);
        }

        @Test
        @DisplayName("throws when no factory registered for id")
        void throwsWhenNoFactory() {
            assertThrows(Exception.class,
                    () -> SerializerRegistry.instance().parseConfig("unknown", rawConfig("k1")));
        }

        @Test
        @DisplayName("id lookup is case-insensitive")
        void idLookupCaseInsensitive() throws Exception {
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("JSON"));

            assertDoesNotThrow(
                    () -> SerializerRegistry.instance().parseConfig("json", rawConfig("k1")));
        }
    }

    @Nested
    @DisplayName("grouping — same key reuses instance")
    class Grouping {

        @BeforeEach
        void resetInstanceCount() {
            TestSerializer.instanceCount = 0;
        }

        @Test
        @DisplayName("same grouping key returns the same instance")
        void sameKeyReturnsSameInstance() throws Exception {
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("test"));

            ItaraSerializerConfig config1 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraSerializerConfig config2 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));

            ItaraSerializer s1 = SerializerRegistry.instance().getOrCreate("test", config1);
            ItaraSerializer s2 = SerializerRegistry.instance().getOrCreate("test", config2);

            assertSame(s1, s2);
            assertEquals(1, TestSerializer.instanceCount,
                    "Only one instance should have been created");
        }

        @Test
        @DisplayName("different grouping keys return different instances")
        void differentKeysReturnDifferentInstances() throws Exception {
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("test"));

            ItaraSerializerConfig config1 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraSerializerConfig config2 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));

            ItaraSerializer s1 = SerializerRegistry.instance().getOrCreate("test", config1);
            ItaraSerializer s2 = SerializerRegistry.instance().getOrCreate("test", config2);

            assertNotSame(s1, s2);
            assertEquals(2, TestSerializer.instanceCount,
                    "Two instances should have been created");
        }

        @Test
        @DisplayName("extra fields not in grouping key do not affect instance reuse")
        void extraFieldsDoNotAffectGrouping() throws Exception {
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("test"));

            ItaraSerializerConfig config1 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-a"));
            ItaraSerializerConfig config2 = SerializerRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-b"));

            ItaraSerializer s1 = SerializerRegistry.instance().getOrCreate("test", config1);
            ItaraSerializer s2 = SerializerRegistry.instance().getOrCreate("test", config2);

            assertSame(s1, s2);
            assertEquals(1, TestSerializer.instanceCount);
        }

        @Test
        @DisplayName("different serializer ids are independent even with same grouping key")
        void differentIdsAreIndependent() throws Exception {
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("alpha"));
            SerializerRegistry.instance().registerFactory(new TestSerializerFactory("beta"));

            ItaraSerializerConfig configA = SerializerRegistry.instance()
                    .parseConfig("alpha", rawConfig("k1"));
            ItaraSerializerConfig configB = SerializerRegistry.instance()
                    .parseConfig("beta", rawConfig("k1"));

            ItaraSerializer sA = SerializerRegistry.instance().getOrCreate("alpha", configA);
            ItaraSerializer sB = SerializerRegistry.instance().getOrCreate("beta", configB);

            assertNotSame(sA, sB);
            assertEquals(2, TestSerializer.instanceCount);
        }

        @Test
        @DisplayName("create() is called exactly once per unique grouping key")
        void createCalledOncePerKey() throws Exception {
            TestSerializerFactory factory = new TestSerializerFactory("test");
            SerializerRegistry.instance().registerFactory(factory);

            for (int i = 0; i < 5; i++) {
                ItaraSerializerConfig config = SerializerRegistry.instance()
                        .parseConfig("test", rawConfig("k1"));
                SerializerRegistry.instance().getOrCreate("test", config);
            }

            assertEquals(1, factory.createCallCount,
                    "create() must be called exactly once for a repeated grouping key");
        }
    }
}
