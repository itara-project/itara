package dev.itara.runtime;

import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.AuthenticationOutcome;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthenticationFactory;
import dev.itara.spi.authentication.ItaraAuthenticationGroupingKey;
import dev.itara.spi.identity.ItaraTransportCredential;
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

@DisplayName("AuthenticationRegistry")
public class AuthenticationRegistryTest {

    @BeforeEach
    void reset() {
        AuthenticationRegistry.instance().reset();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    static class TestGroupingKey implements ItaraAuthenticationGroupingKey {
        private final String value;
        TestGroupingKey(String value) { this.value = value; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TestGroupingKey)) return false;
            return Objects.equals(value, ((TestGroupingKey) o).value);
        }
        @Override public int hashCode() { return Objects.hash(value); }
        @Override public String toString() { return "TestGroupingKey(" + value + ")"; }
    }

    static class TestAuthenticationConfig implements ItaraAuthenticationConfig {
        private final String keyValue;
        private final String extraField;

        TestAuthenticationConfig(String keyValue, String extraField) {
            this.keyValue   = keyValue;
            this.extraField = extraField;
        }

        @Override
        public ItaraAuthenticationGroupingKey groupingKey() {
            return new TestGroupingKey(keyValue);
        }
    }

    static class TestAuthentication implements ItaraAuthentication {
        static int instanceCount = 0;
        final int instanceId;

        TestAuthentication() { instanceId = ++instanceCount; }

        @Override
        public Map<String, String> produceAssertion(ItaraAuthenticationConfig config, ItaraCallTarget target) {
            return Map.of();
        }

        @Override
        public AuthenticationOutcome authenticate(ItaraAuthenticationConfig config, Map<String, String> headers, ItaraTransportCredential transportCredential) {
            return AuthenticationOutcome.accepted();
        }
    }

    static class TestAuthenticationFactory implements ItaraAuthenticationFactory {
        final String authenticationId;
        int parseCallCount  = 0;
        int createCallCount = 0;

        TestAuthenticationFactory(String authenticationId) {
            this.authenticationId = authenticationId;
        }

        @Override
        public String id() { return authenticationId; }

        @Override
        public ItaraAuthenticationConfig parseConfig(AuthenticationConfig config) {
            parseCallCount++;
            String key = config.getParams().getOrDefault("key", "default");
            String extra = config.getParams().getOrDefault("extra", "");
            return new TestAuthenticationConfig(key, extra);
        }

        @Override
        public ItaraAuthentication create(ItaraAuthenticationConfig config) {
            createCallCount++;
            return new TestAuthentication();
        }
    }

    private static AuthenticationConfig rawConfig(String key) {
        return AuthenticationConfig.builder()
                .params(Map.of("key", key))
                .build();
    }

    private static AuthenticationConfig rawConfigWithExtra(String key, String extra) {
        return AuthenticationConfig.builder()
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
            TestAuthenticationFactory factory = new TestAuthenticationFactory("test");
            AuthenticationRegistry.instance().registerFactory(factory);

            AuthenticationRegistry.instance().parseConfig("test", rawConfig("k1"));

            assertEquals(1, factory.parseCallCount);
        }

        @Test
        @DisplayName("throws when no factory registered for id")
        void throwsWhenNoFactory() {
            assertThrows(Exception.class,
                    () -> AuthenticationRegistry.instance().parseConfig("unknown", rawConfig("k1")));
        }

        @Test
        @DisplayName("id lookup is case-insensitive")
        void idLookupCaseInsensitive() throws Exception {
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("MTLS"));

            assertDoesNotThrow(
                    () -> AuthenticationRegistry.instance().parseConfig("mtls", rawConfig("k1")));
        }
    }

    @Nested
    @DisplayName("grouping — same key reuses instance")
    class Grouping {

        @BeforeEach
        void resetInstanceCount() {
            TestAuthentication.instanceCount = 0;
        }

        @Test
        @DisplayName("same grouping key returns the same instance")
        void sameKeyReturnsSameInstance() throws Exception {
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("test"));

            ItaraAuthenticationConfig config1 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraAuthenticationConfig config2 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));

            ItaraAuthentication a1 = AuthenticationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthentication a2 = AuthenticationRegistry.instance().getOrCreate("test", config2);

            assertSame(a1, a2);
            assertEquals(1, TestAuthentication.instanceCount,
                    "Only one instance should have been created");
        }

        @Test
        @DisplayName("different grouping keys return different instances")
        void differentKeysReturnDifferentInstances() throws Exception {
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("test"));

            ItaraAuthenticationConfig config1 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraAuthenticationConfig config2 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));

            ItaraAuthentication a1 = AuthenticationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthentication a2 = AuthenticationRegistry.instance().getOrCreate("test", config2);

            assertNotSame(a1, a2);
            assertEquals(2, TestAuthentication.instanceCount,
                    "Two instances should have been created");
        }

        @Test
        @DisplayName("extra fields not in grouping key do not affect instance reuse")
        void extraFieldsDoNotAffectGrouping() throws Exception {
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("test"));

            ItaraAuthenticationConfig config1 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-a"));
            ItaraAuthenticationConfig config2 = AuthenticationRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-b"));

            ItaraAuthentication a1 = AuthenticationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthentication a2 = AuthenticationRegistry.instance().getOrCreate("test", config2);

            assertSame(a1, a2);
            assertEquals(1, TestAuthentication.instanceCount);
        }

        @Test
        @DisplayName("different authentication ids are independent even with same grouping key")
        void differentIdsAreIndependent() throws Exception {
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("alpha"));
            AuthenticationRegistry.instance().registerFactory(new TestAuthenticationFactory("beta"));

            ItaraAuthenticationConfig configA = AuthenticationRegistry.instance()
                    .parseConfig("alpha", rawConfig("k1"));
            ItaraAuthenticationConfig configB = AuthenticationRegistry.instance()
                    .parseConfig("beta", rawConfig("k1"));

            ItaraAuthentication aA = AuthenticationRegistry.instance().getOrCreate("alpha", configA);
            ItaraAuthentication aB = AuthenticationRegistry.instance().getOrCreate("beta", configB);

            assertNotSame(aA, aB);
            assertEquals(2, TestAuthentication.instanceCount);
        }

        @Test
        @DisplayName("create() is called exactly once per unique grouping key")
        void createCalledOncePerKey() throws Exception {
            TestAuthenticationFactory factory = new TestAuthenticationFactory("test");
            AuthenticationRegistry.instance().registerFactory(factory);

            for (int i = 0; i < 5; i++) {
                ItaraAuthenticationConfig config = AuthenticationRegistry.instance()
                        .parseConfig("test", rawConfig("k1"));
                AuthenticationRegistry.instance().getOrCreate("test", config);
            }

            assertEquals(1, factory.createCallCount,
                    "create() must be called exactly once for a repeated grouping key");
        }
    }
}
