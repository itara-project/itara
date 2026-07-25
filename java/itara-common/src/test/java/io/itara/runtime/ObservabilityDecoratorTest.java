package io.itara.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ObservabilityDecorator")
class ObservabilityDecoratorTest {

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    private ClassLoader originalTccl;

    @BeforeEach
    void captureOriginalTccl() {
        originalTccl = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void restoreOriginalTccl() {
        Thread.currentThread().setContextClassLoader(originalTccl);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface CaptureContract {
        ClassLoader captureTccl();

        void explode();
    }

    static class CaptureImpl implements CaptureContract {
        @Override
        public ClassLoader captureTccl() {
            return Thread.currentThread().getContextClassLoader();
        }

        @Override
        public void explode() {
            throw new RuntimeException("boom");
        }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    // ── wrap() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("wrap")
    class Wrap {

        @Test
        @DisplayName("defines the proxy class under the given classloader")
        void proxyDefinedUnderGivenClassLoader() {
            ClassLoader componentClassLoader = freshClassLoader();

            Object proxy = ObservabilityDecorator.wrap(
                    new CaptureImpl(), "test-component", CaptureContract.class, componentClassLoader);

            assertSame(componentClassLoader, proxy.getClass().getClassLoader());
        }
    }

    // ── invoke() — TCCL swap ────────────────────────────────────────────

    @Nested
    @DisplayName("invoke — TCCL handling")
    class InvokeTccl {

        @Test
        @DisplayName("sets TCCL to the component's classloader during the call, restores it afterward")
        void setsAndRestoresTcclAroundInvocation() {
            ClassLoader componentClassLoader = freshClassLoader();
            ClassLoader ambientClassLoader = freshClassLoader();
            assertNotSame(componentClassLoader, ambientClassLoader, "precondition: the two loaders must differ");

            Thread.currentThread().setContextClassLoader(ambientClassLoader);

            CaptureContract proxy = (CaptureContract) ObservabilityDecorator.wrap(
                    new CaptureImpl(), "test-component", CaptureContract.class, componentClassLoader);

            ClassLoader observedDuringCall = proxy.captureTccl();

            assertSame(componentClassLoader, observedDuringCall,
                    "TCCL during the wrapped call must be the component's own classloader");
            assertSame(ambientClassLoader, Thread.currentThread().getContextClassLoader(),
                    "TCCL must be restored to the ambient value after the call returns");
        }

        @Test
        @DisplayName("restores TCCL even when the delegate throws")
        void restoresTcclEvenWhenDelegateThrows() {
            ClassLoader componentClassLoader = freshClassLoader();
            ClassLoader ambientClassLoader = Thread.currentThread().getContextClassLoader();

            CaptureContract proxy = (CaptureContract) ObservabilityDecorator.wrap(
                    new CaptureImpl(), "test-component", CaptureContract.class, componentClassLoader);

            assertThrows(RuntimeException.class, proxy::explode);
            assertSame(ambientClassLoader, Thread.currentThread().getContextClassLoader());
        }
    }

    // ── Object method bypass ─────────────────────────────────────────────

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("bypass the observability and TCCL-swap pipeline entirely")
        void objectMethodsBypassPipeline() {
            ClassLoader componentClassLoader = freshClassLoader();
            CaptureContract proxy = (CaptureContract) ObservabilityDecorator.wrap(
                    new CaptureImpl(), "test-component", CaptureContract.class, componentClassLoader);

            assertDoesNotThrow(proxy::toString);
        }
    }
}
