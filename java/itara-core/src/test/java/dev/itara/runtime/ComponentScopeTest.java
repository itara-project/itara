package dev.itara.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ComponentScope")
class ComponentScopeTest {

    private static final ClassLoader CL = new ClassLoader() { };

    @AfterEach
    void clear() {
        // Ensure no scope leaks between tests on a reused thread
        ComponentScope.resetForTest();
    }

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory")
    class Factory {

        @Test
        @DisplayName("builds a scope with the given nodeId, componentId, and classLoader")
        void buildsWithGivenFields() {
            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId("orderNode")
                    .componentId("order")
                    .classLoader(CL)
                    .build();

            assertAll(
                    () -> assertEquals("orderNode", scope.getNodeId()),
                    () -> assertEquals("order", scope.getComponentId()),
                    () -> assertSame(CL, scope.getClassLoader())
            );
        }

        @Test
        @DisplayName("rejects a null nodeId")
        void rejectsNullNodeId() {
            assertThrows(NullPointerException.class, () -> new ComponentScope.Factory()
                    .componentId("order")
                    .classLoader(CL)
                    .build());
        }

        @Test
        @DisplayName("rejects a null componentId")
        void rejectsNullComponentId() {
            assertThrows(NullPointerException.class, () -> new ComponentScope.Factory()
                    .nodeId("orderNode")
                    .classLoader(CL)
                    .build());
        }

        @Test
        @DisplayName("rejects a null classLoader")
        void rejectsNullClassLoader() {
            assertThrows(NullPointerException.class, () -> new ComponentScope.Factory()
                    .nodeId("orderNode")
                    .componentId("order")
                    .build());
        }
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("two scopes built with identical field values are not equal — reference identity only")
        void referenceIdentityOnly() {
            ComponentScope a = new ComponentScope.Factory()
                    .nodeId("orderNode").componentId("order").classLoader(CL).build();
            ComponentScope b = new ComponentScope.Factory()
                    .nodeId("orderNode").componentId("order").classLoader(CL).build();

            assertNotSame(a, b);
            // No equals() override — falls back to Object identity, so these must differ
            assertNotEquals(a, b);
        }
    }

    // ── Active-scope access ─────────────────────────────────────────────

    @Nested
    @DisplayName("current()")
    class Current {

        @Test
        @DisplayName("returns null when nothing has been set on this thread")
        void nullByDefault() {
            assertNull(ComponentScope.current());
        }

        @Test
        @DisplayName("reflects the most recently set() scope")
        void reflectsSetScope() {
            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId("orderNode").componentId("order").classLoader(CL).build();

            ComponentScope.set(scope);

            assertSame(scope, ComponentScope.current());
        }

        @Test
        @DisplayName("does not leak between threads")
        void doesNotLeakBetweenThreads() throws InterruptedException {
            AtomicReference<ComponentScope> seenOnOtherThread = new AtomicReference<>();
            Thread preExisting = new Thread(() -> seenOnOtherThread.set(ComponentScope.current()));

            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId("orderNode").componentId("order").classLoader(CL).build();
            ComponentScope.set(scope);

            preExisting.start();
            preExisting.join();

            // A thread not created *by* the thread that set the scope never
            // sees it — InheritableThreadLocal only copies at child-creation
            // time, from whichever thread actually creates the child.
            assertNull(seenOnOtherThread.get());
            // And the original thread's own view is unaffected by the probe.
            assertSame(scope, ComponentScope.current());
        }

        @Test
        @DisplayName("is inherited by a thread created after the scope was set")
        void inheritedByChildThread() throws InterruptedException {
            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId("orderNode").componentId("order").classLoader(CL).build();
            ComponentScope.set(scope);

            AtomicReference<ComponentScope> seenOnChild = new AtomicReference<>();
            Thread child = new Thread(() -> seenOnChild.set(ComponentScope.current()));
            child.start();
            child.join();

            assertSame(scope, seenOnChild.get());
        }
    }

    @Test
    @DisplayName("resetForTest() clears the active scope on this thread")
    void resetForTestClears() {
        ComponentScope scope = new ComponentScope.Factory()
                .nodeId("orderNode").componentId("order").classLoader(CL).build();
        ComponentScope.set(scope);

        ComponentScope.resetForTest();

        assertNull(ComponentScope.current());
    }
}
