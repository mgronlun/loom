/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.misc.ThreadFlock;

/**
 * A basic API for <em>structured concurrency</em>. {@code StructuredTaskScope} supports
 * cases where a task splits into several concurrent subtasks, to be executed in their
 * own threads, and where the subtasks must complete before the main task continues. A
 * {@code StructuredTaskScope} can be used to ensure that the lifetime of a concurrent
 * operation is confined by a <em>syntax block</em>, just like that of a sequential
 * operation in structured programming.
 *
 * <h2>Basic operation</h2>
 *
 * A {@code StructuredTaskScope} is created with one of its public constructors. It defines
 * the {@link #fork(Callable) fork} method to start a thread to execute a task, the {@link
 * #join() join} method to wait for all threads to finish, and the {@link #close() close}
 * method to close the task scope. The API is intended to be used with the {@code
 * try-with-resources} statement. The intention is that code in the try <em>block</em>
 * uses the {@code fork} method to fork threads to execute the subtasks, wait for the
 * threads to finish with the {@code join} method, and then <em>process the results</em>.
 * Each call to the {@code fork} method returns a {@link TaskHandle TaskHandle}. The
 * processing of the results can use {@link TaskHandle#get() TaskHandle.get()} method to
 * get the result of a task that completes successfully. If needed, it can use {@link
 * TaskHandle#exception() TaskHandle.exception()} to get the exception of a task that fails.
 * {@snippet lang=java :
 *     Callable<String> task1 = ...
 *     Callable<Integer> task2 = ...
 *
 *     try (var scope = new StructuredTaskScope<Object>()) {
 *
 *         TaskHandle<String> handle1 = scope.fork(task1);  // @highlight substring="fork"
 *         TaskHandle<Integer> handle2 = scope.fork(task2); // @highlight substring="fork"
 *
 *         scope.join();                                    // @highlight substring="join"
 *
 *         ... process results/exceptions ...
 *
 *     } // close                                           // @highlight substring="close"
 * }
 * <p> The following example forks a collection of homogeneous tasks, waits for all tasks
 * to complete with the {@code join} method, and uses the {@link TaskHandle.State
 * TaskHandle.State} to partition the task handles into one set with the handles to tasks
 * that completed successfully and another for the tasks that failed.
 * {@snippet lang=java :
 *     List<Callable<String>> tasks = ...
 *
 *     try (var scope = new StructuredTaskScope<String>()) {
 *
 *         List<TaskHandle<String>> handles = tasks.stream().map(scope::fork).toList();
 *
 *         scope.join();
 *
 *         Map<Boolean, Set<TaskHandle<String>>> map = handles.stream()
 *                 .collect(Collectors.partitioningBy(h -> h.state() == TaskHandle.State.SUCCESS,
 *                                                    Collectors.toSet()));
 *
 *     } // close
 * }
 *
 * <p> To ensure correct usage, the {@code join} and {@code close} methods may only be
 * invoked by the <em>owner</em> (the thread that opened/created the task scope), and the
 * {@code close} method throws an exception after closing if the owner did not invoke the
 * {@code join} method after forking.
 *
 * <p> {@code StructuredTaskScope} defines the {@link #shutdown() shutdown} method to shut
 * down a task scope without closing it. The {@code shutdown()} method cancels all
 * unfinished subtasks by {@linkplain Thread#interrupt() interrupting} the threads. It
 * prevents new threads from starting in the task scope. If the owner is waiting in the
 * {@code join} method then it will wakeup.
 *
 * <p> Shutdown is used for <em>short-circuiting</em> and allow subclasses to implement
 * <em>policy</em> that does not require all tasks to finish.
 *
 * <h2>Subclasses with policies for common cases</h2>
 *
 * Two subclasses of {@code StructuredTaskScope} are defined to implement policy for
 * common cases:
 * <ol>
 *   <li> {@link ShutdownOnSuccess ShutdownOnSuccess} captures the first result and
 *   shuts down the task scope to interrupt unfinished threads and wakeup the owner. This
 *   class is intended for cases where the result of any subtask will do ("invoke any")
 *   and where there is no need to wait for results of other unfinished tasks. It defines
 *   methods to get the first result or throw an exception if all subtasks fail.
 *   <li> {@link ShutdownOnFailure ShutdownOnFailure} captures the first exception and
 *   shuts down the task scope. This class is intended for cases where the results of all
 *   subtasks are required ("invoke all"); if any subtask fails then the results of other
 *   unfinished subtasks are no longer needed. If defines methods to throw an exception if
 *   any of the subtasks fail.
 * </ol>
 *
 * <p> The following are two examples that use the two classes. In both cases, a pair of
 * subtasks are forked to fetch resources from two URL locations "left" and "right". The
 * first example creates a ShutdownOnSuccess object to capture the result of the first
 * subtask to complete successfully, cancelling the other by way of shutting down the task
 * scope. The main task waits in {@code join} until either subtask completes with a result
 * or both subtasks fail. It invokes {@link ShutdownOnSuccess#result(Function)
 * result(Function)} method to get the captured result. If both subtasks fail then this
 * method throws a {@code WebApplicationException} with the exception from one of the
 * subtasks as the cause.
 * {@snippet lang=java :
 *     try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
 *
 *         scope.fork(() -> fetch(left));
 *         scope.fork(() -> fetch(right));
 *
 *         scope.join();
 *
 *         // @link regex="result(?=\()" target="ShutdownOnSuccess#result" :
 *         String result = scope.result(e -> new WebApplicationException(e));
 *
 *         ...
 *     }
 * }
 * The second example creates a ShutdownOnFailure object to capture the exception of the
 * first subtask to fail, cancelling the other by way of shutting down the task scope. The
 * main task waits in {@link #joinUntil(Instant)} until both subtasks complete with a
 * result, either fails, or a deadline is reached. It invokes {@link
 * ShutdownOnFailure#throwIfFailed(Function) throwIfFailed(Function)} to throw an exception
 * if either subtask fails. This method is a no-op if both subtasks complete successfully.
 * The example uses {@link Supplier#get()} to get the result of each subtask. Using
 * {@code Supplier} instead of {@code TaskHandle} is preferred for common cases where the
 * handle is only used to get the result of a task that completed successfully.
 * {@snippet lang=java :
 *    Instant deadline = ...
 *
 *    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
 *
 *         Supplier<String> supplier1 = scope.fork(() -> query(left));
 *         Supplier<String> supplier2 = scope.fork(() -> query(right));
 *
 *         scope.joinUntil(deadline);
 *
 *         // @link substring="throwIfFailed" target="ShutdownOnFailure#throwIfFailed" :
 *         scope.throwIfFailed(e -> new WebApplicationException(e));
 *
 *         // both subtasks completed successfully
 *         String result = Stream.of(supplier1, supplier2)
 *                 .map(Supplier::get)
 *                 .collect(Collectors.joining(", ", "{ ", " }"));
 *
 *         ...
 *     }
 * }
 *
 * <h2>Extending StructuredTaskScope</h2>
 *
 * {@code StructuredTaskScope} can be extended, and the {@link #handleComplete(TaskHandle)
 * handleComplete} method overridden, to implement policies other than those implemented
 * by {@code ShutdownOnSuccess} and {@code ShutdownOnFailure}. A subclass may, for example,
 * collect the results of subtasks that complete successfully and ignore subtasks that
 * fail. It may collect exceptions when subtasks fail. It may invoke the {@link #shutdown()
 * shutdown} method to shut down and cause {@link #join() join} to wakeup when some
 * condition arises.
 *
 * <p> A subclass will typically define methods to make available results, state, or other
 * outcome to code that executes after the {@code join} method. A subclass that collects
 * results and ignores subtasks that fail may define a method that returns a collection of
 * results. A subclass that implements a policy to shut down when a subtask fails may
 * define a method to get the exception of the first subtask to fail.
 *
 * <p> The following is an example of a {@code StructuredTaskScope} implementation that
 * collects the task handles to all tasks that complete. It defines the method
 * <b>{@code results()}</b> to be used by the main task to getting a map of "task to
 * result" for the subtasks that completed successfully.
 * {@snippet lang=java :
 *     class CollectingScope<T> extends StructuredTaskScope<T> {
 *         private final Queue<TaskHandle<T>> handles = new LinkedTransferQueue<>();
 *
 *         CollectingScope() { }
 *
 *         @Override
 *         protected void handleComplete(TaskHandle<T> handle) {
 *             handles.add(handle);
 *         }
 *
 *         @Override
 *         public CollectingScope<T> join() throws InterruptedException {
 *             super.join();
 *             return this;
 *         }
 *
 *         // returns a map of a task to result for the tasks that completed successfully
 *         public Map<Callable<T>, T> results() {
 *             // @link substring="ensureOwnerAndJoined" target="ensureOwnerAndJoined" :
 *             super.ensureOwnerAndJoined();
 *             return handles.stream()
 *                     .filter(h -> h.state() == TaskHandle.State.SUCCESS)
 *                     .collect(Collectors.toMap(TaskHandle::task, TaskHandle::get));
 *         }
 *     }
 * }
 * <p> The implementations of the {@code results()} method in the example invokes {@link
 * #ensureOwnerAndJoined()} to ensure that the method can only be invoked by the owner
 * thread and only after it has joined.
 *
 * <h2><a id="TreeStructure">Tree structure</a></h2>
 *
 * Task scopes form a tree where parent-child relations are established implicitly when
 * opening a new task scope:
 * <ul>
 *   <li> A parent-child relation is established when a thread started in a task scope
 *   opens its own task scope. A thread started in task scope "A" that opens task scope
 *   "B" establishes a parent-child relation where task scope "A" is the parent of task
 *   scope "B".
 *   <li> A parent-child relation is established with nesting. If a thread opens task
 *   scope "B", then opens task scope "C" (before it closes "B"), then the enclosing task
 *   scope "B" is the parent of the nested task scope "C".
 * </ul>
 *
 * The <i>descendants</i> of a task scope are the child task scopes that it is a parent
 * of, plus the descendants of the child task scopes, recursively.
 *
 * <p> The tree structure supports:
 * <ul>
 *   <li> Inheritance of {@linkplain ScopedValue scoped values} across threads.
 *   <li> Confinement checks. The phrase "threads contained in the task scope" in method
 *   descriptions means threads started in the task scope or descendant scopes.
 * </ul>
 *
 * <p> The following example demonstrates the inheritance of a scoped value. A scoped
 * value {@code USERNAME} is bound to the value "{@code duke}". A {@code StructuredTaskScope}
 * is created and its {@code fork} method invoked to start a thread to execute {@code
 * childTask}. The thread inherits the scoped value <em>bindings</em> captured when
 * creating the task scope. The code in {@code childTask} uses the value of the scoped
 * value and so reads the value "{@code duke}".
 * {@snippet lang=java :
 *     private static final ScopedValue<String> USERNAME = ScopedValue.newInstance();
 *
 *     // @link substring="runWhere" target="ScopedValue#runWhere(ScopedValue, Object, Runnable)" :
 *     ScopedValue.runWhere(USERNAME, "duke", () -> {
 *         try (var scope = new StructuredTaskScope<String>()) {
 *
 *             scope.fork(() -> childTask());           // @highlight substring="fork"
 *             ...
 *          }
 *     });
 *
 *     ...
 *
 *     String childTask() {
 *         // @link substring="get" target="ScopedValue#get()" :
 *         String name = USERNAME.get();   // "duke"
 *         ...
 *     }
 * }
 *
 * <p> {@code StructuredTaskScope} does not define APIs that exposes the tree structure
 * at this time.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * <h2>Memory consistency effects</h2>
 *
 * <p> Actions in the owner thread of, or a thread contained in, the task scope prior to
 * {@linkplain #fork forking} of a {@code Callable} task
 * <a href="{@docRoot}/java.base/java/util/concurrent/package-summary.html#MemoryVisibility">
 * <i>happen-before</i></a> any actions taken by that task, which in turn <i>happen-before</i>
 * the task result is retrieved via its {@code TaskHandle}, or <i>happen-before</i> any
 * actions taken in a thread after {@linkplain #join() joining} of the task scope.
 *
 * @jls 17.4.5 Happens-before Order
 *
 * @param <T> the result type of tasks executed in the task scope
 * @since 21
 */
@PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
public class StructuredTaskScope<T> implements AutoCloseable {
    private final ThreadFactory factory;
    private final ThreadFlock flock;
    private final ReentrantLock shutdownLock = new ReentrantLock();

    // set by owner when it forks, reset by owner when it joins
    private boolean needJoin;

    // states: OPEN -> SHUTDOWN -> CLOSED
    private static final int OPEN     = 0;   // initial state
    private static final int SHUTDOWN = 1;
    private static final int CLOSED   = 2;

    // scope state, set by owner, read by any thread
    private volatile int state;

    /**
     * A handle to a task forked with {@link #fork(Callable)}.
     * @param <T> the result type
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public sealed interface TaskHandle<T> extends Supplier<T> permits TaskRunner {
        /**
         * {@return the task provided to the {@code fork} method}
         */
        Callable<T> task();

        /**
         * Represents the state of a task.
         * @see TaskHandle#state()
         * @since 21
         */
        @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
        enum State {
            /**
             * The task has not completed.
             */
            RUNNING,
            /**
             * The task completed successfully with a result. This is a terminal state.
             * @see TaskHandle#get()
             */
            SUCCESS,
            /**
             * The task failed with an exception. This is a terminal state.
             * @see TaskHandle#exception()
             */
            FAILED,
            /**
             * The task scope was {@linkplain StructuredTaskScope#shutdown() shut down}
             * before it completed. This is a terminal state.
             */
            CANCELLED
        }

        /**
         * {@return the state of the task}
         * When initially {@linkplain #fork(Callable) forked}, and the task scope is not
         * shutdown, the state is {@link State#RUNNING RUNNING}. It transitions to a
         * terminal state when the task completes successfully, fails, or the task scope
         * is shut down.
         */
        State state();

        /**
         * Returns the result, if the task completed successfully.
         * @return the possibly-null result
         * @throws IllegalStateException if the task has not completed, the task failed,
         * or the scope was shutdown before the task completed
         * @throws IllegalStateException if the current thread is the task scope owner
         * and it did not invoke join after forking
         * @see State#SUCCESS
         */
        T get();

        /**
         * {@return the exception, if the task failed with an exception}
         * @throws IllegalStateException if the task has not completed, the task
         * completed successfully, or the scope was shutdown before the task completed
         * @throws IllegalStateException if the current thread is the task scope owner
         * and it did not invoke join after forking
         * @see State#FAILED
         */
        Throwable exception();
    }

    /**
     * Creates a structured task scope with the given name and thread factory. The task
     * scope is optionally named for the purposes of monitoring and management. The thread
     * factory is used to {@link ThreadFactory#newThread(Runnable) create} threads when
     * tasks are {@linkplain #fork(Callable) forked}. The task scope is owned by the
     * current thread.
     *
     * <p> Construction captures the current thread's {@linkplain ScopedValue scoped value}
     * bindings for inheritance by threads started in the task scope. The
     * <a href="#TreeStructure">Tree Structure</a> section in the class description details
     * how parent-child relations are established implicitly for the purpose of inheritance
     * of scoped value bindings.
     *
     * @param name the name of the task scope, can be null
     * @param factory the thread factory
     */
    public StructuredTaskScope(String name, ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory, "'factory' is null");
        this.flock = ThreadFlock.open(name);
    }

    /**
     * Creates an unnamed structured task scope that creates virtual threads. The task
     * scope is owned by the current thread.
     *
     * @implSpec This constructor is equivalent to invoking the 2-arg constructor with a
     * name of {@code null} and a thread factory that creates virtual threads.
     */
    public StructuredTaskScope() {
        this.factory = Thread.ofVirtual().factory();
        this.flock = ThreadFlock.open(null);
    }

    private IllegalStateException newIllegalStateExceptionScopeClosed() {
        return new IllegalStateException("Task scope is closed");
    }

    private IllegalStateException newIllegalStateExceptionNoJoin() {
        return new IllegalStateException("Owner did not invoke join or joinUntil after fork");
    }

    /**
     * Return true if the task scope is shutdown.
     */
    private boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    /**
     * Throws IllegalStateException if the scope is closed, returning the state if not
     * closed.
     */
    private int ensureOpen() {
        int s = state;
        if (s == CLOSED)
            throw newIllegalStateExceptionScopeClosed();
        return s;
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner.
     */
    private void ensureOwner() {
        if (Thread.currentThread() != flock.owner())
            throw new WrongThreadException("Current thread not owner");
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner
     * or a thread contained in the tree.
     */
    private void ensureOwnerOrContainsThread() {
        Thread currentThread = Thread.currentThread();
        if (currentThread != flock.owner() && !flock.containsThread(currentThread))
            throw new WrongThreadException("Current thread not owner or thread in the tree");
    }

    /**
     * Throws IllegalStateException if the current thread is the owner and it did
     * not invoke join after forking
     */
    private void ensureJoinedIfOwner() {
        if (Thread.currentThread() == flock.owner() && needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    /**
     * Ensures that the current thread is the owner of this task scope and that the
     * {@link #join()} or {@link #joinUntil(Instant)} method has been called after
     * {@linkplain #fork(Callable) forking}.
     *
     * @apiNote This method can be used by subclasses that define methods to make available
     * results, state, or other outcome to code intended to execute after the join method.
     *
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws IllegalStateException if the task scope owner did not invoke join after
     * forking
     */
    protected final void ensureOwnerAndJoined() {
        ensureOwner();
        if (needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    /**
     * Invoked when a task completes successfully or fails in this task scope.
     *
     * @implSpec The default implementation throws {@code NullPointerException} if the
     * handle is {@code null}. It throws {@link IllegalArgumentException} if the task has
     * not completed or was cancelled.
     *
     * @apiNote The {@code handleComplete} method should be thread safe. It may be
     * invoked by several threads concurrently.
     *
     * @param handle the handle to the task
     *
     * @throws IllegalArgumentException if the task has not completed or the task was
     * cancelled
     */
    protected void handleComplete(TaskHandle<T> handle) {
        var state = handle.state();
        if (state == TaskHandle.State.RUNNING || state == TaskHandle.State.CANCELLED)
            throw new IllegalArgumentException();
    }

    /**
     * Starts a new thread in this task scope to run the given task.
     *
     * <p> The new thread is created with the task scope's {@link ThreadFactory}. It
     * inherits the current thread's {@linkplain ScopedValue scoped value} bindings. The
     * bindings must match the bindings captured when the task scope was created.
     *
     * <p> The fork method returns a {@link TaskHandle TaskHandle} to the forked task. The
     * handle can be used to obtain the result when the task completes successfully, or the
     * exception when the task fails. To ensure correct usage, the {@link TaskHandle#get()
     * get()} and {@link TaskHandle#exception() exception()} methods may only be called by
     * the task scope owner after it has waited for all threads to finish with the {@link
     * #join() join} or {@link #joinUntil(Instant)} methods.
     *
     * <p> If this task scope is {@linkplain #shutdown() shutdown} (or in the process
     * of shutting down) then the task will not run. In that case, this method returns
     * a handle representing a {@linkplain TaskHandle.State#CANCELLED cancelled} task.
     *
     * <p> If the task completes before the task scope is {@link #shutdown() shutdown}
     * then the {@link #handleComplete(TaskHandle) handleComplete} method
     * is invoked to consume the completed task. If the task scope is shutdown at or around
     * the same time that the task completes then the {@code handleComplete} method may
     * or may not be invoked.
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @implSpec This method may be overridden for customization purposes, wrapping tasks
     * for example. If overridden, the subclass must invoke {@code super.fork} to start a
     * new thread in this task scope.
     *
     * @param task the task to run
     * @param <U> the result type
     * @return the handle to the forked task
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner or a
     * thread contained in the task scope
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <U extends T> TaskHandle<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task, "'task' is null");
        int s = ensureOpen();   // throws ISE if closed

        TaskRunner<U> runner = new TaskRunner<>(this, task);
        boolean started = false;

        if (s < SHUTDOWN) {
            // create thread to run task
            Thread thread = factory.newThread(runner);
            if (thread == null) {
                throw new RejectedExecutionException("Rejected by thread factory");
            }

            // attempt to start the thread
            try {
                flock.start(thread);
                started = true;
            } catch (IllegalStateException e) {
                // shutdown by another thread or underlying flock is shutdown
            }
        }

        if (!started) {
            runner.cancelUnstarted();
        } else if (Thread.currentThread() == flock.owner() && !needJoin) {
            // force owner to join
            needJoin = true;
        }

        // return handle to forked or cancelled task
        return runner;
    }

    /**
     * Wait for all threads to finish or the task scope to shut down.
     */
    private void implJoin(Duration timeout)
        throws InterruptedException, TimeoutException
    {
        ensureOwner();
        int s = ensureOpen();  // throws ISE if closed
        needJoin = false;
        if (s != OPEN)
            return;

        // wait for all threads, wakeup, interrupt, or timeout
        if (timeout != null) {
            flock.awaitAll(timeout);
        } else {
            flock.awaitAll();
        }
    }

    /**
     * Wait for all threads in this task scope to finish or the task scope to shut down.
     * This method waits until all threads started in this task scope finish execution
     * (of both task and the {@link #handleComplete(TaskHandle) handleComplete} method),
     * the {@link #shutdown() shutdown} method is invoked to shut down the task scope,
     * or the current thread is {@linkplain Thread#interrupt() interrupted}.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @implSpec This method may be overridden for customization purposes or to return a
     * more specific return type. If overridden, the subclass must invoke {@code
     * super.join} to ensure that the method waits for threads in this task scope to
     * finish.
     *
     * @return this task scope
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws InterruptedException if interrupted while waiting
     */
    public StructuredTaskScope<T> join() throws InterruptedException {
        try {
            implJoin(null);
        } catch (TimeoutException e) {
            throw new InternalError();
        }
        return this;
    }

    /**
     * Wait for all threads in this task scope to finish or the task scope to shut down,
     * up to the given deadline. This method waits until all threads started in the task
     * scope finish execution (of both task and the {@link #handleComplete(TaskHandle)
     * handleComplete} method), the {@link #shutdown() shutdown} method is invoked to
     * shut down the task scope, the current thread is {@linkplain Thread#interrupt()
     * interrupted}, or the deadline is reached.
     *
     * * <p> This method may only be invoked by the task scope owner.
     *
     * @implSpec This method may be overridden for customization purposes or to return a
     * more specific return type. If overridden, the subclass must invoke {@code
     * super.joinUntil} to ensure that the method waits for threads in this task scope to
     * finish.
     *
     * @param deadline the deadline
     * @return this task scope
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException if the deadline is reached while waiting
     */
    public StructuredTaskScope<T> joinUntil(Instant deadline)
        throws InterruptedException, TimeoutException
    {
        Duration timeout = Duration.between(Instant.now(), deadline);
        implJoin(timeout);
        return this;
    }

    /**
     * Interrupt all unfinished threads.
     */
    private void implInterruptAll() {
        flock.threads().forEach(t -> {
            if (t != Thread.currentThread()) {
                try {
                    t.interrupt();
                } catch (Throwable ignore) { }
            }
        });
    }

    @SuppressWarnings("removal")
    private void interruptAll() {
        if (System.getSecurityManager() == null) {
            implInterruptAll();
        } else {
            PrivilegedAction<Void> pa = () -> {
                implInterruptAll();
                return null;
            };
            AccessController.doPrivileged(pa);
        }
    }

    /**
     * Shutdown the task scope if not already shutdown. Return true if this method
     * shutdowns the task scope, false if already shutdown.
     */
    private boolean implShutdown() {
        shutdownLock.lock();
        try {
            if (state < SHUTDOWN) {
                // prevent new threads from starting
                flock.shutdown();

                // set status before interrupting tasks
                state = SHUTDOWN;

                // interrupt all unfinished threads
                interruptAll();

                return true;
            } else {
                // already shutdown
                return false;
            }
        } finally {
            shutdownLock.unlock();
        }
    }

    /**
     * Shut down this task scope without closing it. Shutting down a task scope prevents
     * new threads from starting, interrupts all unfinished threads, and causes the
     * {@link #join() join} method to wakeup. Shutdown is useful for cases where the
     * results of unfinished subtasks are no longer needed. It will typically be called
     * by the {@link #handleComplete(TaskHandle)} implementation of a subclass that
     * implements a policy to discard unfinished tasks once some outcome is reached.
     *
     * <p> More specifically, this method:
     * <ul>
     * <li> {@linkplain Thread#interrupt() Interrupts} all unfinished threads in the
     * task scope (except the current thread).
     * <li> Wakes up the task scope owner if it is waiting in {@link #join()} or {@link
     * #joinUntil(Instant)}. If the task scope owner is not waiting then its next call to
     * {@code join} or {@code joinUntil} will return immediately.
     * </ul>
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @implSpec This method may be overridden for customization purposes. If overridden,
     * the subclass must invoke {@code super.shutdown} to ensure that the method shuts
     * down the task scope.
     *
     * @apiNote
     * There may be threads that have not finished because they are executing code that
     * did not respond (or respond promptly) to thread interrupt. This method does not wait
     * for these threads. When the owner invokes the {@link #close() close} method
     * to close the task scope then it will wait for the remaining threads to finish.
     *
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner or
     * a thread contained in the task scope
     */
    public void shutdown() {
        ensureOwnerOrContainsThread();
        int s = ensureOpen();  // throws ISE if closed
        if (s < SHUTDOWN && implShutdown())
            flock.wakeup();
    }

    /**
     * Closes this task scope.
     *
     * <p> This method first shuts down the task scope (as if by invoking the {@link
     * #shutdown() shutdown} method). It then waits for the threads executing any
     * unfinished tasks to finish. If interrupted then this method will continue to
     * wait for the threads to finish before completing with the interrupt status set.
     *
     * <p> This method may only be invoked by the task scope owner. If the task scope
     * is already closed then the task scope owner invoking this method has no effect.
     *
     * <p> A {@code StructuredTaskScope} is intended to be used in a <em>structured
     * manner</em>. If this method is called to close a task scope before nested task
     * scopes are closed then it closes the underlying construct of each nested task scope
     * (in the reverse order that they were created in), closes this task scope, and then
     * throws {@link StructureViolationException}.
     * Similarly, if this method is called to close a task scope while executing with
     * {@linkplain ScopedValue scoped value} bindings, and the task scope was created
     * before the scoped values were bound, then {@code StructureViolationException} is
     * thrown after closing the task scope.
     *
     * If a thread terminates without first closing task scopes that it owns then
     * termination will cause the underlying construct of each of its open tasks scopes to
     * be closed. Closing is performed in the reverse order that the task scopes were
     * created in. Thread termination may therefore be delayed when the task scope owner
     * has to wait for threads forked in these task scopes to finish.
     *
     * @implSpec This method may be overridden for customization purposes. If overridden,
     * the subclass must invoke {@code super.close} to close the task scope.
     *
     * @throws IllegalStateException thrown after closing the task scope if the task scope
     * owner did not invoke join after forking
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws StructureViolationException if a structure violation was detected
     */
    @Override
    public void close() {
        ensureOwner();
        int s = state;
        if (s == CLOSED)
            return;

        try {
            if (s < SHUTDOWN)
                implShutdown();
            flock.close();
        } finally {
            state = CLOSED;
        }

        if (needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String name = flock.name();
        if (name != null) {
            sb.append(name);
            sb.append('/');
        }
        sb.append(Objects.toIdentityString(this));
        int s = state;
        if (s == SHUTDOWN)
            sb.append("/shutdown");
        else if (s == CLOSED)
            sb.append("/closed");
        return sb.toString();
    }

    /**
     * Runs the task specified to fork. If the task completes before the scope is shutdown
     * then it is runs the handleComplete method with the task result or exception.
     */
    private static final class TaskRunner<T> implements TaskHandle<T>, Runnable {
        private static final AltResult RESULT_NULL = new AltResult(TaskHandle.State.SUCCESS);
        private static final AltResult RESULT_CANCELLED = new AltResult(TaskHandle.State.CANCELLED);
        private static final VarHandle RESULT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                RESULT = l.findVarHandle(TaskRunner.class, "result", Object.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private record AltResult(TaskHandle.State state, Throwable exception) {
            AltResult(TaskHandle.State state) {
                this(state, null);
            }
            AltResult(Throwable ex) {
                this(TaskHandle.State.FAILED, ex);
            }
        }

        private final StructuredTaskScope<T> scope;
        private final Callable<T> task;
        private volatile Object result;

        @SuppressWarnings("unchecked")
        TaskRunner(StructuredTaskScope<? super T> scope, Callable<? extends T> task) {
            this.scope = (StructuredTaskScope<T>) scope;
            this.task = (Callable<T>) task;
        }

        /**
         * Task not run, set to cancelled state
         */
        void cancelUnstarted() {
            assert result == null;
            result = RESULT_CANCELLED;
        }

        @Override
        public void run() {
            T result = null;
            Throwable ex = null;
            try {
                result = task.call();
            } catch (Throwable e) {
                ex = e;
            }

            // invoke handleComplete if the scope has not shutdown
            if (!scope.isShutdown()) {
                if (ex == null) {
                    // task succeeded
                    Object r = (result != null) ? result : RESULT_NULL;
                    if (RESULT.compareAndSet(this, null, r)) {
                        scope.handleComplete(this);
                    }
                } else {
                    // task failed
                    if (RESULT.compareAndSet(this, null, new AltResult(ex))) {
                        scope.handleComplete(this);
                    }
                }
            }
        }

        @Override
        public Callable<T> task() {
            return task;
        }

        @Override
        public TaskHandle.State state() {
            Object result = this.result;
            if (result == null && scope.isShutdown()) {
                // cancelled
                RESULT.compareAndSet(this, null, RESULT_CANCELLED);
                result = this.result;
            }
            if (result == null) {
                return TaskHandle.State.RUNNING;
            } else if (result instanceof AltResult alt) {
                // null, failed or cancelled
                return alt.state();
            } else {
                return TaskHandle.State.SUCCESS;
            }
        }

        @Override
        public T get() {
            scope.ensureJoinedIfOwner();
            Object result = this.result;
            if (result instanceof AltResult) {
                if (result == RESULT_NULL) return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            throw new IllegalStateException("Task did not complete successfully");
        }

        @Override
        public Throwable exception() {
            scope.ensureJoinedIfOwner();
            if (result instanceof AltResult alt && alt.state() == TaskHandle.State.FAILED) {
                return alt.exception();
            }
            throw new IllegalStateException("Task did not fail with an exception");
        }

        @Override
        public String toString() {
            String stateAsString = switch (state()) {
                case RUNNING   -> "[Not completed]";
                case SUCCESS   -> "[Completed successfully]";
                case FAILED    -> {
                    Throwable ex = ((AltResult) result).exception();
                    yield "[Failed: " + ex + "]";
                }
                case CANCELLED -> "[Cancelled]";
            };
            return Objects.toIdentityString(this ) + stateAsString;
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the result of the first subtask to
     * complete {@linkplain TaskHandle.State#SUCCESS successfully}. Once captured, it
     * invokes the {@linkplain #shutdown() shutdown} method to interrupt unfinished threads
     * and wakeup the task scope owner. The policy implemented by this class is intended
     * for cases where the result of any subtask will do ("invoke any") and where the
     * results of other unfinished subtask are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @param <T> the result type
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnSuccess<T> extends StructuredTaskScope<T> {
        private static final Object RESULT_NULL = new Object();
        private static final VarHandle FIRST_RESULT;
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_RESULT = l.findVarHandle(ShutdownOnSuccess.class, "firstResult", Object.class);
                FIRST_EXCEPTION = l.findVarHandle(ShutdownOnSuccess.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Object firstResult;
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnSuccess} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when tasks are {@linkplain #fork(Callable) forked}. The task scope is
         * owned by the current thread.
         *
         * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
         * value} bindings for inheritance by threads started in the task scope. The
         * <a href="#TreeStructure">Tree Structure</a> section in the class description
         * details how parent-child relations are established implicitly for the purpose
         * of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null
         * @param factory the thread factory
         */
        public ShutdownOnSuccess(String name, ThreadFactory factory) {
            super(name, factory);
        }

        /**
         * Constructs a new unnamed {@code ShutdownOnSuccess} that creates virtual threads.
         *
         * @implSpec This constructor is equivalent to invoking the 2-arg constructor with
         * a name of {@code null} and a thread factory that creates virtual threads.
         */
        public ShutdownOnSuccess() {
            super(null, Thread.ofVirtual().factory());
        }

        /**
         * Shut down this task scope when invoked for the first time with a task that
         * completed {@linkplain TaskHandle.State#SUCCESS successfully}.
         *
         * @throws IllegalArgumentException {@inheritDoc}
         */
        @Override
        protected void handleComplete(TaskHandle<T> handle) {
            super.handleComplete(handle);

            if (firstResult != null) {
                // already captured a result
                return;
            }

            if (handle.state() == TaskHandle.State.SUCCESS) {
                // task succeeded
                T result = handle.get();
                Object r = (result != null) ? result : RESULT_NULL;
                if (FIRST_RESULT.compareAndSet(this, null, r)) {
                    shutdown();
                }
            } else if (firstException == null) {
                // capture the exception thrown by the first task that failed
                FIRST_EXCEPTION.compareAndSet(this, null, handle.exception());
            }
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnSuccess<T> join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnSuccess<T> joinUntil(Instant deadline)
            throws InterruptedException, TimeoutException
        {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * {@return the result of the first subtask that completed {@linkplain
         * TaskHandle.State#SUCCESS successfully}}
         *
         * <p> When no subtask completed successfully, but a task {@linkplain
         * TaskHandle.State#FAILED failed}, then {@code ExecutionException} is thrown with
         * the failed task's exception as the {@linkplain Throwable#getCause() cause}.
         *
         * @throws ExecutionException if no subtasks completed successfully but a subtask
         * failed
         * @throws IllegalStateException if the handleComplete method was not invoked with
         * a successful or failed task, or the task scope owner did not invoke join after
         * forking
         * @throws WrongThreadException if the current thread is not the task scope owner
         */
        public T result() throws ExecutionException {
            ensureOwnerAndJoined();

            Object result = firstResult;
            if (result == RESULT_NULL) {
                return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            Throwable ex = firstException;
            if (ex != null) {
                throw new ExecutionException(ex);
            }

            throw new IllegalStateException("No completed subtasks");
        }

        /**
         * Returns the result of the first subtask that completed {@linkplain
         * TaskHandle.State#SUCCESS successfully}, otherwise throws an exception produced
         * by the given exception supplying function.
         *
         * <p> When no subtask completed successfully, but a task {@linkplain
         * TaskHandle.State#FAILED failed}, then the exception supplying function is
         * invoked with failed task's exception.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @return the result of the first subtask that completed with a result
         *
         * @throws X if no subtask completed with a result
         * @throws IllegalStateException if the handleComplete method was not invoked with
         * a successful or failed task, or the task scope owner did not invoke join after
         * forking
         * @throws WrongThreadException if the current thread is not the task scope owner
         */
        public <X extends Throwable> T result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            ensureOwnerAndJoined();

            Object result = firstResult;
            if (result == RESULT_NULL) {
                return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }

            throw new IllegalStateException("No completed subtasks");
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the exception of the first subtask to
     * {@linkplain TaskHandle.State#FAILED fail}. Once captured, it invokes the {@linkplain
     * #shutdown() shutdown} method to interrupt unfinished threads and wakeup the task
     * scope owner. The policy implemented by this class is intended for cases where the
     * results for all subtasks are required ("invoke all"); if any subtask fails then the
     * results of other unfinished subtasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnFailure extends StructuredTaskScope<Object> {
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(ShutdownOnFailure.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnFailure} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when tasks are {@linkplain #fork(Callable) forked}. The task scope
         * is owned by the current thread.
         *
         * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
         * value} bindings for inheritance by threads started in the task scope. The
         * <a href="#TreeStructure">Tree Structure</a> section in the class description
         * details how parent-child relations are established implicitly for the purpose
         * of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null
         * @param factory the thread factory
         */
        public ShutdownOnFailure(String name, ThreadFactory factory) {
            super(name, factory);
        }

        /**
         * Constructs a new unnamed {@code ShutdownOnFailure} that creates virtual threads.
         *
         * @implSpec This constructor is equivalent to invoking the 2-arg constructor with
         * a name of {@code null} and a thread factory that creates virtual threads.
         */
        public ShutdownOnFailure() {
            super(null, Thread.ofVirtual().factory());
        }

        /**
         * Shut down this task scope when invoked for the first time with a task that
         * {@linkplain TaskHandle.State#FAILED failed}.
         *
         * @throws IllegalArgumentException {@inheritDoc}
         */
        @Override
        protected void handleComplete(TaskHandle<Object> handle) {
            super.handleComplete(handle);
            if (handle.state() == TaskHandle.State.FAILED
                    && firstException == null
                    && FIRST_EXCEPTION.compareAndSet(this, null, handle.exception())) {
                shutdown();
            }
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnFailure join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnFailure joinUntil(Instant deadline)
            throws InterruptedException, TimeoutException
        {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * Returns the exception of the first subtask that {@linkplain TaskHandle.State#FAILED
         * failed}. If no subtasks failed then an empty {@code Optional} is returned.
         *
         * @return the exception for the first subtask to fail or an empty optional if no
         * subtasks failed
         *
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public Optional<Throwable> exception() {
            ensureOwnerAndJoined();
            return Optional.ofNullable(firstException);
        }

        /**
         * Throws if a subtask {@linkplain TaskHandle.State#FAILED failed}.
         * If any subtask failed with an exception then {@code ExecutionException} is
         * thrown with the exception of the first subtask to fail as the {@linkplain
         * Throwable#getCause() cause}. This method does nothing if no subtasks failed.
         *
         * @throws ExecutionException if a subtask failed
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public void throwIfFailed() throws ExecutionException {
            ensureOwnerAndJoined();
            Throwable exception = firstException;
            if (exception != null)
                throw new ExecutionException(exception);
        }

        /**
         * Throws the exception produced by the given exception supplying function if a
         * subtask {@linkplain TaskHandle.State#FAILED failed}. If any subtask failed with
         * an exception then the function is invoked with the exception of the first
         * subtask to fail. The exception returned by the function is thrown. This method
         * does nothing if no subtasks failed.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         *
         * @throws X produced by the exception supplying function
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public <X extends Throwable>
        void throwIfFailed(Function<Throwable, ? extends X> esf) throws X {
            ensureOwnerAndJoined();
            Objects.requireNonNull(esf);
            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
        }
    }
}
