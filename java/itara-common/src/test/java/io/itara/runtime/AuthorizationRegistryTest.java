package io.itara.runtime;

import io.itara.spi.authorization.AuthorizationConfig;
import io.itara.spi.authorization.AuthorizationDecision;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorizationFactory;
import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;
import io.itara.spi.identity.ItaraIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AuthorizationRegistry")
public class AuthorizationRegistryTest {

    @BeforeEach
    void reset() {
        AuthorizationRegistry.instance().reset();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    static class TestGroupingKey implements ItaraAuthorizationGroupingKey {
        private final String value;
        TestGroupingKey(String value) { this.value = value; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TestGroupingKey)) return false;
            return Objects.equals(value, ((TestGroupingKey) o).value);
        }
        @Override public int hashCode() { return Objects.hash(value); }
        @Override public String toString() { return "TestGroupingKey(" + value + ")"; }
    }

    static class TestAuthorizationConfig implements ItaraAuthorizationConfig {
        private final String keyValue;
        private final String extraField;

        TestAuthorizationConfig(String keyValue, String extraField) {
            this.keyValue   = keyValue;
            this.extraField = extraField;
        }

        @Override
        public ItaraAuthorizationGroupingKey groupingKey() {
            return new TestGroupingKey(keyValue);
        }
    }

    static class TestAuthorization implements ItaraAuthorization {
        static int instanceCount = 0;
        final int instanceId;

        TestAuthorization() { instanceId = ++instanceCount; }

        @Override
        public AuthorizationDecision authorize(ItaraAuthorizationConfig config, Optional<ItaraIdentity> identity, ItaraCallTarget target, Map<String, String> headers) {
            return AuthorizationDecision.permit();
        }
    }

    static class TestAuthorizationFactory implements ItaraAuthorizationFactory {
        final String authorizationId;
        int parseCallCount  = 0;
        int createCallCount = 0;

        TestAuthorizationFactory(String authorizationId) {
            this.authorizationId = authorizationId;
        }

        @Override
        public String id() { return authorizationId; }

        @Override
        public ItaraAuthorizationConfig parseConfig(AuthorizationConfig config) {
            parseCallCount++;
            String key = config.getParams().getOrDefault("key", "default");
            String extra = config.getParams().getOrDefault("extra", "");
            return new TestAuthorizationConfig(key, extra);
        }

        @Override
        public ItaraAuthorization create(ItaraAuthorizationConfig config) {
            createCallCount++;
            return new TestAuthorization();
        }
    }

    private static AuthorizationConfig rawConfig(String key) {
        return AuthorizationConfig.builder()
                .params(Map.of("key", key))
                .build();
    }

    private static AuthorizationConfig rawConfigWithExtra(String key, String extra) {
        return AuthorizationConfig.builder()
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
            TestAuthorizationFactory factory = new TestAuthorizationFactory("test");
            AuthorizationRegistry.instance().registerFactory(factory);

            AuthorizationRegistry.instance().parseConfig("test", rawConfig("k1"));

            assertEquals(1, factory.parseCallCount);
        }

        @Test
        @DisplayName("throws when no factory registered for id")
        void throwsWhenNoFactory() {
            assertThrows(Exception.class,
                    () -> AuthorizationRegistry.instance().parseConfig("unknown", rawConfig("k1")));
        }

        @Test
        @DisplayName("id lookup is case-insensitive")
        void idLookupCaseInsensitive() throws Exception {
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("RBAC"));

            assertDoesNotThrow(
                    () -> AuthorizationRegistry.instance().parseConfig("rbac", rawConfig("k1")));
        }
    }

    @Nested
    @DisplayName("grouping — same key reuses instance")
    class Grouping {

        @BeforeEach
        void resetInstanceCount() {
            TestAuthorization.instanceCount = 0;
        }

        @Test
        @DisplayName("same grouping key returns the same instance")
        void sameKeyReturnsSameInstance() throws Exception {
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("test"));

            ItaraAuthorizationConfig config1 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraAuthorizationConfig config2 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));

            ItaraAuthorization a1 = AuthorizationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthorization a2 = AuthorizationRegistry.instance().getOrCreate("test", config2);

            assertSame(a1, a2);
            assertEquals(1, TestAuthorization.instanceCount,
                    "Only one instance should have been created");
        }

        @Test
        @DisplayName("different grouping keys return different instances")
        void differentKeysReturnDifferentInstances() throws Exception {
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("test"));

            ItaraAuthorizationConfig config1 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfig("k1"));
            ItaraAuthorizationConfig config2 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfig("k2"));

            ItaraAuthorization a1 = AuthorizationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthorization a2 = AuthorizationRegistry.instance().getOrCreate("test", config2);

            assertNotSame(a1, a2);
            assertEquals(2, TestAuthorization.instanceCount,
                    "Two instances should have been created");
        }

        @Test
        @DisplayName("extra fields not in grouping key do not affect instance reuse")
        void extraFieldsDoNotAffectGrouping() throws Exception {
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("test"));

            ItaraAuthorizationConfig config1 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-a"));
            ItaraAuthorizationConfig config2 = AuthorizationRegistry.instance()
                    .parseConfig("test", rawConfigWithExtra("k1", "extra-b"));

            ItaraAuthorization a1 = AuthorizationRegistry.instance().getOrCreate("test", config1);
            ItaraAuthorization a2 = AuthorizationRegistry.instance().getOrCreate("test", config2);

            assertSame(a1, a2);
            assertEquals(1, TestAuthorization.instanceCount);
        }

        @Test
        @DisplayName("different authorization ids are independent even with same grouping key")
        void differentIdsAreIndependent() throws Exception {
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("alpha"));
            AuthorizationRegistry.instance().registerFactory(new TestAuthorizationFactory("beta"));

            ItaraAuthorizationConfig configA = AuthorizationRegistry.instance()
                    .parseConfig("alpha", rawConfig("k1"));
            ItaraAuthorizationConfig configB = AuthorizationRegistry.instance()
                    .parseConfig("beta", rawConfig("k1"));

            ItaraAuthorization aA = AuthorizationRegistry.instance().getOrCreate("alpha", configA);
            ItaraAuthorization aB = AuthorizationRegistry.instance().getOrCreate("beta", configB);

            assertNotSame(aA, aB);
            assertEquals(2, TestAuthorization.instanceCount);
        }

        @Test
        @DisplayName("create() is called exactly once per unique grouping key")
        void createCalledOncePerKey() throws Exception {
            TestAuthorizationFactory factory = new TestAuthorizationFactory("test");
            AuthorizationRegistry.instance().registerFactory(factory);

            for (int i = 0; i < 5; i++) {
                ItaraAuthorizationConfig config = AuthorizationRegistry.instance()
                        .parseConfig("test", rawConfig("k1"));
                AuthorizationRegistry.instance().getOrCreate("test", config);
            }

            assertEquals(1, factory.createCallCount,
                    "create() must be called exactly once for a repeated grouping key");
        }
    }
}
