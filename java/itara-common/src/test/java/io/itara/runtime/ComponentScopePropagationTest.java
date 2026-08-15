package io.itara.runtime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ComponentScopePropagation")
class ComponentScopePropagationTest {

    private static final ClassLoader CL = new ClassLoader() { };

    // Created once, before any test ever sets a scope — deliberately
    // standing in for "a pool that predates any component's scope being
    // active" (the ForkJoinPool.commonPool() case), so InheritableThreadLocal
    // cannot be the thing making these tests pass.
    private static ExecutorService sharedPool;

    @BeforeAll
    static void createSharedPool() throws ExecutionException, InterruptedException, TimeoutException {
        sharedPool = Executors.newSingleThreadExecutor();
        // ThreadPoolExecutor creates its worker thread lazily, on first
        // submission — not at construction. Force that now, while no test
        // has set a scope yet, so the thread genuinely predates any scope,
        // which is the premise every test in this class relies on.
        sharedPool.submit(() -> null).get(5, TimeUnit.SECONDS);
    }

    @AfterAll
    static void shutdownSharedPool() {
        sharedPool.shutdownNow();
    }

    @AfterEach
    void clear() {
        ComponentScope.resetForTest();
    }

    private static ComponentScope scope(String nodeId, String componentId) {
        return new ComponentScope.Factory()
                .nodeId(nodeId).componentId(componentId).classLoader(CL).build();
    }

    // ── wrap(Runnable) / wrap(Callable) in isolation ────────────────────

    @Nested
    @DisplayName("wrap(Runnable) / wrap(Callable)")
    class BareWrap {

        @Test
        @DisplayName("wrap() rejects a null task")
        void rejectsNullTask() {
            assertThrows(NullPointerException.class, () -> ComponentScopePropagation.wrap((Runnable) null));
            assertThrows(NullPointerException.class, () -> ComponentScopePropagation.wrap((Callable<?>) null));
        }

        @Test
        @DisplayName("with no active scope, returns the same Runnable instance unwrapped")
        void passthroughWhenNoScopeRunnable() {
            Runnable task = () -> { };
            assertSame(task, ComponentScopePropagation.wrap(task));
        }

        @Test
        @DisplayName("with no active scope, returns the same Callable instance unwrapped")
        void passthroughWhenNoScopeCallable() {
            Callable<String> task = () -> "result";
            assertSame(task, ComponentScopePropagation.wrap(task));
        }

        @Test
        @DisplayName("without wrap(), a pre-existing shared pool does not inherit the submitting thread's scope")
        void unwrappedSubmissionDoesNotInheritScope() throws Exception {
            ComponentScope scope = scope("orderNode", "order");

            ComponentScope.set(scope);
            // Submitted directly to sharedPool, bypassing ComponentScopePropagation entirely.
            Future<ComponentScope> future = sharedPool.submit((Callable<ComponentScope>) ComponentScope::current);

            assertNull(future.get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("a wrapped Runnable, run on a thread that already existed, sees the capturing thread's scope")
        void propagatesRunnableToPreExistingThread() throws InterruptedException, ExecutionException, TimeoutException {
            ComponentScope scope = scope("orderNode", "order");

            AtomicReference<ComponentScope> seenInsidePool = new AtomicReference<>();
            Runnable task = () -> seenInsidePool.set(ComponentScope.current());

            ComponentScope.set(scope);
            Runnable wrapped = ComponentScopePropagation.wrap(task);

            sharedPool.submit(wrapped).get(5, TimeUnit.SECONDS);

            assertSame(scope, seenInsidePool.get());
        }

        @Test
        @DisplayName("a wrapped Callable, run on a thread that already existed, sees the capturing thread's scope")
        void propagatesCallableToPreExistingThread() throws Exception {
            ComponentScope scope = scope("orderNode", "order");

            Callable<ComponentScope> task = ComponentScope::current;

            ComponentScope.set(scope);
            Callable<ComponentScope> wrapped = ComponentScopePropagation.wrap(task);

            ComponentScope seenInsidePool = sharedPool.submit(wrapped).get(5, TimeUnit.SECONDS);

            assertSame(scope, seenInsidePool);
        }

        @Test
        @DisplayName("the executing thread's scope is restored to its prior baseline after the task completes")
        void restoresExecutingThreadAfterTask() throws Exception {
            ComponentScope scope = scope("orderNode", "order");
            ComponentScope.set(scope);
            sharedPool.submit(ComponentScopePropagation.wrap((Runnable) () -> { })).get(5, TimeUnit.SECONDS);
            ComponentScope.resetForTest();

            // A later, unscoped submission must not see the earlier task's scope leftover.
            AtomicReference<ComponentScope> seenLater = new AtomicReference<>();
            sharedPool.submit(() -> seenLater.set(ComponentScope.current())).get(5, TimeUnit.SECONDS);

            assertNull(seenLater.get());
        }
    }

    // ── wrap(ExecutorService) ────────────────────────────────────────────

    @Nested
    @DisplayName("wrap(ExecutorService)")
    class WrappedExecutor {

        @Test
        @DisplayName("wrap() rejects a null executor")
        void rejectsNullExecutor() {
            assertThrows(NullPointerException.class, () -> ComponentScopePropagation.wrap((ExecutorService) null));
        }

        @Test
        @DisplayName("execute() propagates the submitting thread's scope")
        void executePropagates() throws InterruptedException {
            ComponentScope scope = scope("orderNode", "order");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            AtomicReference<ComponentScope> seenInsidePool = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            ComponentScope.set(scope);
            wrapped.execute(() -> {
                seenInsidePool.set(ComponentScope.current());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertSame(scope, seenInsidePool.get());
        }

        @Test
        @DisplayName("submit(Callable) propagates the submitting thread's scope")
        void submitCallablePropagates() throws Exception {
            ComponentScope scope = scope("orderNode", "order");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            ComponentScope.set(scope);
            Future<ComponentScope> future = wrapped.submit((Callable<ComponentScope>) ComponentScope::current);

            assertSame(scope, future.get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("submit(Runnable, result) propagates the submitting thread's scope")
        void submitRunnableWithResultPropagates() throws Exception {
            ComponentScope scope = scope("orderNode", "order");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            AtomicReference<ComponentScope> seenInsidePool = new AtomicReference<>();
            ComponentScope.set(scope);
            Future<String> future = wrapped.submit(() -> seenInsidePool.set(ComponentScope.current()), "done");

            assertSame("done", future.get(5, TimeUnit.SECONDS));
            assertSame(scope, seenInsidePool.get());
        }

        @Test
        @DisplayName("consecutive submissions under different scopes each see their own capturing scope, not a stale one")
        void perCallCaptureNotSticky() throws Exception {
            ComponentScope orderScope = scope("orderNode", "order");
            ComponentScope inventoryScope = scope("inventoryNode", "inventory");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            ComponentScope.set(orderScope);
            Future<ComponentScope> first = wrapped.submit((Callable<ComponentScope>) ComponentScope::current);
            assertSame(orderScope, first.get(5, TimeUnit.SECONDS));

            ComponentScope.set(inventoryScope);
            Future<ComponentScope> second = wrapped.submit((Callable<ComponentScope>) ComponentScope::current);
            assertSame(inventoryScope, second.get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("invokeAll() propagates the same captured scope to every task in the batch")
        void invokeAllPropagatesToWholeBatch() throws Exception {
            ComponentScope scope = scope("orderNode", "order");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            List<Callable<ComponentScope>> tasks = Arrays.asList(
                    ComponentScope::current, ComponentScope::current, ComponentScope::current);

            ComponentScope.set(scope);
            List<Future<ComponentScope>> results = wrapped.invokeAll(tasks);

            for (Future<ComponentScope> result : results) {
                assertSame(scope, result.get(5, TimeUnit.SECONDS));
            }
        }

        @Test
        @DisplayName("invokeAny() propagates the captured scope to the winning task")
        void invokeAnyPropagates() throws ExecutionException, InterruptedException {
            ComponentScope scope = scope("orderNode", "order");
            ExecutorService wrapped = ComponentScopePropagation.wrap(sharedPool);

            List<Callable<ComponentScope>> tasks = Arrays.asList(ComponentScope::current, ComponentScope::current);

            ComponentScope.set(scope);
            ComponentScope winner = wrapped.invokeAny(tasks);

            assertSame(scope, winner);
        }

        @Test
        @DisplayName("does not affect the underlying executor's own lifecycle")
        void doesNotOwnDelegateLifecycle() {
            ExecutorService independentPool = Executors.newSingleThreadExecutor();
            ExecutorService wrapped = ComponentScopePropagation.wrap(independentPool);

            wrapped.shutdown();

            assertTrue(independentPool.isShutdown());
        }
    }
}
