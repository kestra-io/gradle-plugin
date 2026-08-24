package io.kestra.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BuildService that caps concurrent non-priority Test tasks to (maxWorkers - reserved) while any
 * priority task is still running, then releases the reserved permits once all priority tasks finish.
 *
 * Lifecycle:
 *   1. Plugin registers the service.
 *   2. Plugin calls arm() from taskGraph.whenReady with the actual task graph.
 *   3. Non-priority Test tasks call acquire(path) in doFirst.
 *   4. The service implements OperationCompletionListener; the plugin registers it via
 *      BuildEventsListenerRegistry.onTaskCompletion. finished(path) handles both permit
 *      release and priority-counter decrement.
 */
abstract class TestSlotService implements BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {

    @Override
    BuildServiceParameters.None getParameters() { return null }

    private volatile Semaphore semaphore = null
    private volatile int reservedCount = 0
    private final AtomicInteger pendingPriority = new AtomicInteger(0)
    private final AtomicBoolean released = new AtomicBoolean(false)

    // Tracks which light-task paths currently hold a permit so we can release idempotently.
    private final Set<String> held = Collections.synchronizedSet(new HashSet<>())
    // Priority task paths we are tracking (to recognise them in onFinish).
    private final Set<String> priorityPaths = Collections.synchronizedSet(new HashSet<>())

    // WorkerLeaseService is Gradle internal API obtained defensively; null if unavailable.
    private volatile Object workerLeaseService = null

    /**
     * Called once from taskGraph.whenReady. Idempotent after first call.
     *
     * @param priorityTaskPaths exact task paths (:core:test, :core:unitTest, …) considered priority
     * @param limited           how many permits non-priority tasks may hold concurrently
     * @param reserved          how many extra permits to release when all priority tasks finish
     * @param leaseService      WorkerLeaseService (nullable) — used to block without squatting a worker slot
     */
    synchronized void arm(Set<String> priorityTaskPaths, int limited, int reserved, Object leaseService) {
        if (semaphore != null) return   // already armed
        this.priorityPaths.addAll(priorityTaskPaths)
        this.pendingPriority.set(priorityTaskPaths.size())
        this.reservedCount = reserved
        this.workerLeaseService = leaseService
        this.semaphore = new Semaphore(limited, true)
    }

    /** Called by non-priority Test tasks in doFirst. No-op when disarmed. */
    void acquire(String taskPath) {
        Semaphore s = semaphore
        if (s == null) return
        Object leases = workerLeaseService
        if (leases != null) {
            try {
                // Release the worker lease while blocking so priority tasks can start.
                leases.blocking { s.acquire() }
                held.add(taskPath)
                return
            } catch (Throwable ignored) {
                // Fall through to plain acquire on any internal-API incompatibility.
            }
        }
        s.acquire()
        held.add(taskPath)
    }

    /** Called by the OperationCompletionListener for every finished task. */
    void finished(String taskPath) {
        Semaphore s = semaphore
        if (s == null) return

        if (priorityPaths.contains(taskPath)) {
            int remaining = pendingPriority.decrementAndGet()
            if (remaining <= 0 && released.compareAndSet(false, true)) {
                s.release(reservedCount)
            }
        } else if (held.remove(taskPath)) {
            s.release()
        }
    }

    @Override
    void onFinish(FinishEvent event) {
        if (event instanceof TaskFinishEvent) {
            finished(event.descriptor.taskPath)
        }
    }

    @Override
    void close() {
        // Drain any remaining permits so blocked tasks are not leaked on build failure.
        Semaphore s = semaphore
        if (s != null && !held.isEmpty()) {
            s.release(held.size())
            held.clear()
        }
    }
}
