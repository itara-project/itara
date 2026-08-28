package dev.itara.agent;

import dev.itara.exceptions.ItaraReconstructibleException;
import dev.itara.exceptions.ItaraReconstructibleExceptionFactory;
import dev.itara.runtime.ReconstructibleExceptionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReconstructibleExceptionRegistry")
public class ReconstructibleExceptionRegistryTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    static class StubFactory implements ItaraReconstructibleExceptionFactory {
        private final String contractId;

        StubFactory(String contractId) {
            this.contractId = contractId;
        }

        @Override
        public String contractId() { return contractId; }

        @Override
        public Optional<ItaraReconstructibleException> reconstruct(
                String errorTypeId, String message) {
            return Optional.empty();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("returns empty when no factory is registered for the contract")
        void returnsEmptyWhenNotRegistered() {
            Optional<ItaraReconstructibleExceptionFactory> result =
                    ReconstructibleExceptionRegistry.instance().get("contract-not-registered");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns the registered factory for the contract")
        void returnsRegisteredFactory() {
            StubFactory factory = new StubFactory("registry-test-get");
            ReconstructibleExceptionRegistry.instance().register(factory);

            Optional<ItaraReconstructibleExceptionFactory> result =
                    ReconstructibleExceptionRegistry.instance().get("registry-test-get");

            assertTrue(result.isPresent());
            assertSame(factory, result.get());
        }
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("second registration for the same contract replaces the first")
        void duplicateReplacesFirst() {
            StubFactory first  = new StubFactory("registry-test-duplicate");
            StubFactory second = new StubFactory("registry-test-duplicate");

            ReconstructibleExceptionRegistry.instance().register(first);
            ReconstructibleExceptionRegistry.instance().register(second);

            Optional<ItaraReconstructibleExceptionFactory> result =
                    ReconstructibleExceptionRegistry.instance().get("registry-test-duplicate");

            assertTrue(result.isPresent());
            assertSame(second, result.get());
        }
    }
}
