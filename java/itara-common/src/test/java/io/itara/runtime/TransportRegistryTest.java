package io.itara.runtime;

import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.spi.transport.ItaraTransportFactory;
import io.itara.spi.transport.ItaraTransportGroupingKey;
import io.itara.spi.transport.TransportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TransportRegistry")
public class TransportRegistryTest {

    @BeforeEach
    void reset() {
        TransportRegistry.instance().reset();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    /**
     * Grouping key that equals by a single string value.
     */
    static class TestGroupingKey implements ItaraTransportGroupingKey {
        private final String value;
        TestGroupingKey(String value) { this.value = value; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TestGroupingKey)) return false;
            return Objects.equals(value, ((TestGroupingKey) o).value);
        }
        @Override public int hashCode() { return Objects.hash(value); }
        @Override public String toString() { return "TestGroupingKey(" + value + ")"; }
    }

    /**
     * Config that wraps a grouping key value and an extra field
     * that does NOT participate in grouping — to verify that two
     * configs with the same key but different extra fields share
     * one instance.
     */
    static class TestTransportConfig implements ItaraTransportConfig {
        private final String keyValue;
        private final String extraField;

        TestTransportConfig(String keyValue, String extraField) {
            this.keyValue   = keyValue;
            this.extraField = extraField;
        }

        @Override
        public ItaraTransportGroupingKey groupingKey() {
            return new TestGroupingKey(keyValue);
        }
    }

    /**
     * Minimal transport stub — counts how many instances were created.
     */
    static class TestTransport implements ItaraTransport {
        static int instanceCount = 0;
        final int instanceId;

        TestTransport() { instanceId = ++instanceCount; }

        @Override
        public byte[] send(ItaraCallTarget target, byte[] payload,
                           Map<String, String> headers, ItaraTransportConfig config,
                           Duration timeout) { return new byte[0]; }

        @Override
        public void registerListener(String componentId, ItaraTransportConfig config,
                                     DispatchHandler dispatcher) {}

        @Override public void start() {}
        @Override public void stop() {}
    }

    /**
     * Factory that parses a "key" param from TransportConfig.params
     * and uses it as the grouping key.
     */
    static class TestTransportFactory implements ItaraTransportFactory {
        final String transportId;
        int parseCallCount   = 0;
        int createCallCount  = 0;

        TestTransportFactory(String transportId) {
            this.transportId = transportId;
        }

        @Override
        public String id() { return transportId; }

        @Override
        public ItaraTransportConfig parseConfig(TransportConfig config) {
            parseCallCount++;
            String key = config.getParams().getOrDefault("key", "default");
            String extra = config.getParams().getOrDefault("extra", "");
            return new TestTransportConfig(key, extra);
        }

        @Override
        public ItaraTransport create(ItaraTransportConfig config) {
            createCallCount++;
            return new TestTransport();
        }
    }

    private static TransportConfig rawConfig(String key) {
        return TransportConfig.builder()
                .params(Map.of("key", key))
                .build();
    }

    private static TransportConfig rawConfigWithExtra(String key, String extra) {
        return TransportConfig.builder()
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
            TestTransportFactory factory = new TestTransportFactory("test");
            TransportRegistry.instance().registerFactory(factory);

            TransportRegistry.instance().parseConfig("test", rawConfig("k1"));

            assertEquals(1, factory.parseCallCount);
        }

        @Test
        @DisplayName("throws when no factory registered for id")
        void throwsWhenNoFactory() {
            assertThrows(Exception.class,
                    () -> TransportRegistry.instance().parseConfig("unknown", rawConfig("k1")));
        }

        @Test
        @DisplayName("id lookup is case-insensitive")
        void idLookupCaseInsensitive() throws Exception {
            TransportRegistry.instance().registerFactory(new TestTransportFactory("HTTP"));

            assertDoesNotThrow(
                    () -> TransportRegistry.instance().parseConfig("http", rawConfig("k1")));
        }
    }

    @Nested
    @DisplayName("grouping — same key reuses instance")
    class Grouping {

        @BeforeEach
        void resetInstanceCount() {
            TestTransport.instanceCount = 0;
        }

        @Test
        @DisplayName("same grouping key returns the same instance")
        void sameKeyReturnsSameInstance() throws Exception {
            TransportRegistry.instance().registerFactory(new TestTransportFactory("test"));

            ItaraTransportConfig config1 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraTransportConfig config2 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));

            ItaraTransport t1 = TransportRegistry.instance().getOrCreate("test", config1);
            ItaraTransport t2 = TransportRegistry.instance().getOrCreate("test", config2);

            assertSame(t1, t2);
            assertEquals(1, TestTransport.instanceCount,
                    "Only one instance should have been created");
        }

        @Test
        @DisplayName("different grouping keys return different instances")
        void differentKeysReturnDifferentInstances() throws Exception {
            TransportRegistry.instance().registerFactory(new TestTransportFactory("test"));

            ItaraTransportConfig config1 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraTransportConfig config2 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));

            ItaraTransport t1 = TransportRegistry.instance().getOrCreate("test", config1);
            ItaraTransport t2 = TransportRegistry.instance().getOrCreate("test", config2);

            assertNotSame(t1, t2);
            assertEquals(2, TestTransport.instanceCount,
                    "Two instances should have been created");
        }

        @Test
        @DisplayName("extra fields not in grouping key do not affect instance reuse")
        void extraFieldsDoNotAffectGrouping() throws Exception {
            TransportRegistry.instance().registerFactory(new TestTransportFactory("test"));

            ItaraTransportConfig config1 = TransportRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-a"));
            ItaraTransportConfig config2 = TransportRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-b"));

            ItaraTransport t1 = TransportRegistry.instance().getOrCreate("test", config1);
            ItaraTransport t2 = TransportRegistry.instance().getOrCreate("test", config2);

            assertSame(t1, t2);
            assertEquals(1, TestTransport.instanceCount);
        }

        @Test
        @DisplayName("different transport ids are independent even with same grouping key")
        void differentIdsAreIndependent() throws Exception {
            TransportRegistry.instance().registerFactory(new TestTransportFactory("alpha"));
            TransportRegistry.instance().registerFactory(new TestTransportFactory("beta"));

            ItaraTransportConfig configA = TransportRegistry.instance()
                    .parseConfig("alpha", rawConfig("k1"));
            ItaraTransportConfig configB = TransportRegistry.instance()
                    .parseConfig("beta", rawConfig("k1"));

            ItaraTransport tA = TransportRegistry.instance().getOrCreate("alpha", configA);
            ItaraTransport tB = TransportRegistry.instance().getOrCreate("beta", configB);

            assertNotSame(tA, tB);
            assertEquals(2, TestTransport.instanceCount);
        }

        @Test
        @DisplayName("create() is called exactly once per unique grouping key")
        void createCalledOncePerKey() throws Exception {
            TestTransportFactory factory = new TestTransportFactory("test");
            TransportRegistry.instance().registerFactory(factory);

            for (int i = 0; i < 5; i++) {
                ItaraTransportConfig config = TransportRegistry.instance()
                        .parseConfig("test", rawConfig("k1"));
                TransportRegistry.instance().getOrCreate("test", config);
            }

            assertEquals(1, factory.createCallCount,
                    "create() must be called exactly once for a repeated grouping key");
        }
    }

    @Nested
    @DisplayName("lifecycle — startAll and stopAll")
    class Lifecycle {

        @Test
        @DisplayName("startAll() calls start() on every created instance")
        void startAllCallsStartOnAll() throws Exception {
            StartStopTrackingTransport.startCount = 0;

            TransportRegistry.instance().registerFactory(
                    new TrackingFactory("test", () -> new StartStopTrackingTransport()));

            ItaraTransportConfig c1 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraTransportConfig c2 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));
            TransportRegistry.instance().getOrCreate("test", c1);
            TransportRegistry.instance().getOrCreate("test", c2);

            TransportRegistry.instance().startAll();

            assertEquals(2, StartStopTrackingTransport.startCount);
        }

        @Test
        @DisplayName("stopAll() calls stop() on every created instance")
        void stopAllCallsStopOnAll() throws Exception {
            StartStopTrackingTransport.stopCount = 0;

            TransportRegistry.instance().registerFactory(
                    new TrackingFactory("test", () -> new StartStopTrackingTransport()));

            ItaraTransportConfig c1 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraTransportConfig c2 = TransportRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));
            TransportRegistry.instance().getOrCreate("test", c1);
            TransportRegistry.instance().getOrCreate("test", c2);

            TransportRegistry.instance().stopAll();

            assertEquals(2, StartStopTrackingTransport.stopCount);
        }

        @Test
        @DisplayName("startAll() on empty registry does not throw")
        void startAllOnEmptyRegistryDoesNotThrow() {
            assertDoesNotThrow(() -> TransportRegistry.instance().startAll());
        }
    }

    // ── Lifecycle fixtures ────────────────────────────────────────────────

    static class StartStopTrackingTransport implements ItaraTransport {
        static int startCount = 0;
        static int stopCount  = 0;

        @Override public void start() { startCount++; }
        @Override public void stop()  { stopCount++; }

        @Override
        public byte[] send(ItaraCallTarget target, byte[] payload,
                           Map<String, String> headers, ItaraTransportConfig config,
                           Duration timeout) { return new byte[0]; }

        @Override
        public void registerListener(String componentId, ItaraTransportConfig config,
                                     DispatchHandler dispatcher) {}
    }

    interface TransportSupplier { ItaraTransport get(); }

    static class TrackingFactory implements ItaraTransportFactory {
        private final String id;
        private final TransportSupplier supplier;

        TrackingFactory(String id, TransportSupplier supplier) {
            this.id       = id;
            this.supplier = supplier;
        }

        @Override public String id() { return id; }

        @Override
        public ItaraTransportConfig parseConfig(TransportConfig config) {
            return new TestTransportConfig(
                    config.getParams().getOrDefault("key", "default"), "");
        }

        @Override
        public ItaraTransport create(ItaraTransportConfig config) {
            return supplier.get();
        }
    }
}
