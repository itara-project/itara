package dev.itara.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Propagates the submitting thread's ComponentScope onto the thread that
 * actually executes a task, for thread pools InheritableThreadLocal cannot
 * cover on its own.
 *
 * <p>InheritableThreadLocal (see ComponentScope) is complete and sufficient
 * for any pool a component creates and owns for itself — inheritance at
 * pool-creation time is permanently correct because a scope never changes
 * for the life of its node (see component-scope-design.md, "Thread pools
 * and inheritance"). This utility exists only for the case that leaves
 * uncovered: a pool shared across components with different scopes, or one
 * that predates any component's scope being active — ForkJoinPool.commonPool()
 * being the standing example. It is not intended for general use; using a
 * component-owned pool directly, without this utility, is expected and
 * correct.
 *
 * <p>Each wrap() call captures ComponentScope.current() at the moment it is
 * called — i.e. on the submitting thread, at submission time. If no scope
 * is active on the submitting thread, the task is returned unwrapped: there
 * is nothing to propagate, and ComponentScopeHandle already guarantees every
 * scoped task restores the executing thread to its exact prior baseline, so
 * an unwrapped task can never observe state a scoped task left behind.
 */
public final class ComponentScopePropagation {

    private ComponentScopePropagation() {
    }

    /**
     * Wraps a Runnable so that, when it runs — on whatever thread that
     * turns out to be — it runs under the scope active on the calling
     * thread right now.
     *
     * @param task the task to wrap
     * @return a wrapped task that opens the captured scope before running,
     *         or the original task unchanged if no scope is currently active
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "[Itara] ComponentScopePropagation.wrap() requires a non-null task.");
        ComponentScope captured = ComponentScope.current();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(captured)) {
                task.run();
            }
        };
    }

    /**
     * Wraps a Callable so that, when it runs — on whatever thread that
     * turns out to be — it runs under the scope active on the calling
     * thread right now.
     *
     * @param task the task to wrap
     * @return a wrapped task that opens the captured scope before running,
     *         or the original task unchanged if no scope is currently active
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "[Itara] ComponentScopePropagation.wrap() requires a non-null task.");
        ComponentScope captured = ComponentScope.current();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(captured)) {
                return task.call();
            }
        };
    }

    /**
     * Wraps an ExecutorService so that every task submitted through it —
     * execute(), submit(), invokeAll(), invokeAny() — propagates whatever
     * scope is active on the submitting thread at the moment of submission,
     * individually, per call.
     *
     * <p>The returned ExecutorService delegates everything else (lifecycle:
     * shutdown/awaitTermination/etc.) straight through to the underlying
     * executor, which this utility does not own and does not affect the
     * lifecycle of.
     *
     * @param executor the executor to wrap
     * @return a scope-propagating ExecutorService backed by {@code executor}
     */
    public static ExecutorService wrap(ExecutorService executor) {
        Objects.requireNonNull(executor, "[Itara] ComponentScopePropagation.wrap() requires a non-null executor.");
        return new PropagatingExecutorService(executor);
    }

    private static final class PropagatingExecutorService implements ExecutorService {

        private final ExecutorService delegate;

        PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(wrap(task), result);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return delegate.invokeAny(wrapAll(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(wrapAll(tasks), timeout, unit);
        }

        private <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
            List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                wrapped.add(wrap(task));
            }
            return wrapped;
        }

        // ── Lifecycle — delegated straight through, untouched ──────────────

        @Override public void shutdown()                                   { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow()                      { return delegate.shutdownNow(); }
        @Override public boolean isShutdown()                              { return delegate.isShutdown(); }
        @Override public boolean isTerminated()                            { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
