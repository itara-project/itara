package io.itara.runtime;

import io.itara.api.ItaraActivator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraRegistry")
class ItaraRegistryTest {

    // Activator instances are created via getDeclaredConstructor().newInstance(),
    // so every fixture activator below is a static, top-level-visible,
    // no-arg-constructible class — never a non-static inner class.

    @BeforeEach
    void reset() {
        ItaraRegistry.instance().reset();
        ORDER_LOG.clear();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface TestContract {
        String hello();
    }

    static class TestContractImpl implements TestContract {
        @Override
        public String hello() {
            return "hello";
        }
    }

    public static class SimpleActivator implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            return new TestContractImpl();
        }
    }

    public static class FailingActivator implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            throw new RuntimeException("activation deliberately failed");
        }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    // ── registerActivator ────────────────────────────────────────────────

    @Nested
    @DisplayName("registerActivator")
    class RegisterActivator {

        @Test
        @DisplayName("4-arg overload throws NullPointerException when classloader is null")
        void fourArgOverloadRejectsNullClassLoader() {
            assertThrows(NullPointerException.class, () ->
                    ItaraRegistry.instance().registerActivator(
                            "test", SimpleActivator.class, TestContract.class, null));
        }

        @Test
        @DisplayName("3-arg overload defaults to the calling thread's context classloader")
        void threeArgOverloadDefaultsToCurrentTccl() {
            ClassLoader expected = Thread.currentThread().getContextClassLoader();

            ItaraRegistry.instance().registerActivator("test", SimpleActivator.class, TestContract.class);

            assertSame(expected, ItaraRegistry.instance().getComponentClassLoader("test"));
        }
    }

    // ── decorate() (exercised via get()) ────────────────────────────────

    @Nested
    @DisplayName("decorate")
    class Decorate {

        @Test
        @DisplayName("always wraps in a proxy, regardless of registered observer count")
        void alwaysDecoratesRegardlessOfObserverCount() {
            assertEquals(0, ObserverRegistry.instance().size(), "precondition: no observers registered");

            ItaraRegistry.instance().registerActivator(
                    "test", SimpleActivator.class, TestContract.class, freshClassLoader());

            TestContract result = ItaraRegistry.instance().get("test", TestContract.class);

            assertTrue(Proxy.isProxyClass(result.getClass()),
                    "component must be decorated even with zero registered observers");
        }

        @Test
        @DisplayName("proxy is defined under the component's own registered classloader, not the ambient TCCL")
        void proxyDefinedUnderComponentClassLoaderNotAmbientTccl() {
            ClassLoader componentClassLoader = freshClassLoader();
            ClassLoader ambientClassLoader = freshClassLoader();
            assertNotSame(componentClassLoader, ambientClassLoader, "precondition: the two loaders must differ");

            ItaraRegistry.instance().registerActivator(
                    "test", SimpleActivator.class, TestContract.class, componentClassLoader);

            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            current.setContextClassLoader(ambientClassLoader);
            try {
                TestContract result = ItaraRegistry.instance().get("test", TestContract.class);

                assertSame(componentClassLoader, result.getClass().getClassLoader());
            } finally {
                current.setContextClassLoader(previous);
            }
        }
    }

    // ── activateAllLocal ─────────────────────────────────────────────────

    // Static log, not an instance field — RecordingActivator* below are
    // static top-level-visible classes with no reference to the test
    // instance, so they record here instead.
    static final List<String> ORDER_LOG = new ArrayList<>();

    public static class RecordingActivatorAlpha implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            ORDER_LOG.add("alpha");
            return new TestContractImpl();
        }
    }

    public static class RecordingActivatorBravo implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            ORDER_LOG.add("bravo");
            return new TestContractImpl();
        }
    }

    public static class RecordingActivatorCharlie implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            ORDER_LOG.add("charlie");
            return new TestContractImpl();
        }
    }

    @Nested
    @DisplayName("activateAllLocal")
    class ActivateAllLocal {

        @Test
        @DisplayName("activates local components in deterministic, sorted order")
        void activatesInSortedOrder() {
            // Registered deliberately out of alphabetical order.
            ItaraRegistry.instance().registerActivator(
                    "charlie", RecordingActivatorCharlie.class, TestContract.class, freshClassLoader());
            ItaraRegistry.instance().registerActivator(
                    "alpha", RecordingActivatorAlpha.class, TestContract.class, freshClassLoader());
            ItaraRegistry.instance().registerActivator(
                    "bravo", RecordingActivatorBravo.class, TestContract.class, freshClassLoader());

            ItaraRegistry.instance().activateAllLocal();

            assertEquals(List.of("alpha", "bravo", "charlie"), ORDER_LOG);
        }

        @Test
        @DisplayName("aborts on the first activation failure, fails fast")
        void abortsOnFirstFailure() {
            ItaraRegistry.instance().registerActivator(
                    "a-fails", FailingActivator.class, TestContract.class, freshClassLoader());
            ItaraRegistry.instance().registerActivator(
                    "z-would-succeed", SimpleActivator.class, TestContract.class, freshClassLoader());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> ItaraRegistry.instance().activateAllLocal());

            assertTrue(ex.getMessage().contains("a-fails"));
        }
    }

    // ── Circular dependency detection ───────────────────────────────────

    public static class SelfReferencingActivator implements ItaraActivator {
        @Override
        public Object activate(ItaraRegistry registry) {
            // Activating the same component id, recursively, on the same
            // thread — must be caught, not stack-overflow.
            return registry.get("self", TestContract.class);
        }
    }

    @Nested
    @DisplayName("circular dependency detection")
    class CircularDependency {

        @Test
        @DisplayName("throws IllegalStateException rather than recursing indefinitely")
        void throwsRatherThanRecursingForever() {
            ItaraRegistry.instance().registerActivator(
                    "self", SelfReferencingActivator.class, TestContract.class, freshClassLoader());

            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().get("self", TestContract.class));
        }
    }

    // ── Topology errors ──────────────────────────────────────────────────

    @Nested
    @DisplayName("topology errors")
    class TopologyErrors {

        @Test
        @DisplayName("get() throws for an unregistered component id")
        void getThrowsForUnregisteredId() {
            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().get("does-not-exist", TestContract.class));
        }

        @Test
        @DisplayName("getRawImplementation() throws for an unregistered component id")
        void getRawImplementationThrowsForUnregisteredId() {
            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().getRawImplementation("does-not-exist", TestContract.class));
        }

        @Test
        @DisplayName("successfully registered component does not throw")
        void registeredComponentDoesNotThrow() {
            ItaraRegistry.instance().registerActivator(
                    "test", SimpleActivator.class, TestContract.class, freshClassLoader());

            assertDoesNotThrow(() -> ItaraRegistry.instance().get("test", TestContract.class));
        }
    }
}
