package dev.itara.failuresemantics.builtin;

import dev.itara.spi.failuresemantics.FailureSemanticsConfig;
import dev.itara.spi.failuresemantics.ItaraFailureSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BuiltInFailureSemanticsFactory")
public class BuiltInFailureSemanticsFactoryTest {

    private BuiltInFailureSemanticsFactory factory;

    @BeforeEach
    void setUp() {
        factory = new BuiltInFailureSemanticsFactory();
    }

    @Test
    @DisplayName("type() returns 'built-in'")
    void typeReturnsBuiltIn() {
        assertEquals("built-in", factory.type());
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates instance with defaults when config is empty")
        void createsWithDefaults() throws Exception {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder().build();

            ItaraFailureSemantics result = factory.create(config);

            assertNotNull(result);
            assertInstanceOf(BuiltInFailureSemantics.class, result);
        }

        @Test
        @DisplayName("creates instance with explicit maxAttempts")
        void createsWithExplicitMaxAttempts() throws Exception {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .maxAttempts(5)
                    .build();

            assertDoesNotThrow(() -> factory.create(config));
        }

        @Test
        @DisplayName("creates instance with timeout")
        void createsWithTimeout() throws Exception {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .timeout(Duration.ofSeconds(2))
                    .build();

            assertDoesNotThrow(() -> factory.create(config));
        }

        @Test
        @DisplayName("creates instance with waitDuration param")
        void createsWithWaitDuration() throws Exception {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .params(Map.of("waitDuration", "PT1S"))
                    .build();

            assertDoesNotThrow(() -> factory.create(config));
        }

        @Test
        @DisplayName("creates instance with retryNonIdempotent param")
        void createsWithRetryNonIdempotent() throws Exception {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .params(Map.of("retryNonIdempotent", "true"))
                    .build();

            assertDoesNotThrow(() -> factory.create(config));
        }

        @Test
        @DisplayName("throws when maxAttempts is less than 1")
        void throwsOnInvalidMaxAttempts() {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .maxAttempts(0)
                    .build();

            assertThrows(IllegalArgumentException.class, () -> factory.create(config));
        }

        @Test
        @DisplayName("throws when waitDuration param is not a valid duration")
        void throwsOnInvalidWaitDuration() {
            FailureSemanticsConfig config = FailureSemanticsConfig.builder()
                    .params(Map.of("waitDuration", "not-a-duration"))
                    .build();

            assertThrows(Exception.class, () -> factory.create(config));
        }
    }
}
