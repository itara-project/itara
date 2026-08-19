package io.itara.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ComponentScopeHandle")
class ComponentScopeHandleTest {

    private static final ClassLoader CL_A = new ClassLoader() { };
    private static final ClassLoader CL_B = new ClassLoader() { };

    private ClassLoader originalTccl;

    @BeforeEach
    void captureOriginalTccl() {
        originalTccl = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void clear() {
        ComponentScope.resetForTest();
        Thread.currentThread().setContextClassLoader(originalTccl);
    }

    private static ComponentScope scope(String nodeId, String componentId, ClassLoader cl) {
        return new ComponentScope.Factory()
                .nodeId(nodeId).componentId(componentId).classLoader(cl).build();
    }

    @Test
    @DisplayName("open() rejects a null scope")
    void openRejectsNull() {
        assertThrows(NullPointerException.class, () -> ComponentScopeHandle.open(null));
    }

    @Nested
    @DisplayName("single open/close")
    class SingleCrossing {

        @Test
        @DisplayName("open() makes current() reflect the new scope")
        void openSetsScope() {
            ComponentScope scope = scope("orderNode", "order", CL_A);

            try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
                assertSame(scope, ComponentScope.current());
            }
        }

        @Test
        @DisplayName("open() swaps the thread's context classloader to the scope's classloader")
        void openSwapsTccl() {
            ComponentScope scope = scope("orderNode", "order", CL_A);

            try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
                assertSame(CL_A, Thread.currentThread().getContextClassLoader());
            }
        }

        @Test
        @DisplayName("close() restores the previous scope (null, if none was active)")
        void closeRestoresPreviousScope() {
            assertNull(ComponentScope.current());
            ComponentScope scope = scope("orderNode", "order", CL_A);

            ComponentScopeHandle handle = ComponentScopeHandle.open(scope);
            handle.close();

            assertNull(ComponentScope.current());
        }

        @Test
        @DisplayName("close() restores the previous classloader")
        void closeRestoresPreviousTccl() {
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            ComponentScope scope = scope("orderNode", "order", CL_A);

            ComponentScopeHandle handle = ComponentScopeHandle.open(scope);
            handle.close();

            assertSame(before, Thread.currentThread().getContextClassLoader());
        }
    }

    @Nested
    @DisplayName("nesting")
    class Nesting {

        @Test
        @DisplayName("a call two components deep restores the right scope at each level on the way out")
        void nestsCorrectly() {
            ComponentScope outer = scope("orderNode", "order", CL_A);
            ComponentScope inner = scope("inventoryNode", "inventory", CL_B);

            try (ComponentScopeHandle outerHandle = ComponentScopeHandle.open(outer)) {
                assertSame(outer, ComponentScope.current());
                assertSame(CL_A, Thread.currentThread().getContextClassLoader());

                try (ComponentScopeHandle innerHandle = ComponentScopeHandle.open(inner)) {
                    assertSame(inner, ComponentScope.current());
                    assertSame(CL_B, Thread.currentThread().getContextClassLoader());
                }

                // Closing inner must restore outer, not null and not inner's TCCL
                assertSame(outer, ComponentScope.current());
                assertSame(CL_A, Thread.currentThread().getContextClassLoader());
            }

            // Closing outer must restore whatever was active before either — nothing
            assertNull(ComponentScope.current());
        }
    }

    @Nested
    @DisplayName("restoration on exception")
    class RestorationOnException {

        @Test
        @DisplayName("try-with-resources restores scope and TCCL even when the guarded code throws")
        void restoresOnThrow() {
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            ComponentScope scope = scope("orderNode", "order", CL_A);

            assertThrows(RuntimeException.class, () -> {
                try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
                    throw new RuntimeException("simulated failure inside the guarded block");
                }
            });

            assertNull(ComponentScope.current());
            assertSame(before, Thread.currentThread().getContextClassLoader());
        }
    }
}
