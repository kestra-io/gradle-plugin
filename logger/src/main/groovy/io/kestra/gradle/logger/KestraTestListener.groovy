package io.kestra.gradle.logger

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult

import java.util.concurrent.ConcurrentHashMap

/**
 * One instance per Test task. Forwards JUnit events to the shared {@link LoggerService}, which does
 * all the actual rendering — this class only carries the per-task context (module name, task path,
 * test source roots for GitHub annotations) that the BuildService itself must not hold onto.
 *
 * A Test task may run its forks concurrently (maxParallelForks > 1), so callbacks can arrive from
 * multiple threads for different tests at once; the descriptor->id map is a ConcurrentHashMap for
 * that reason.
 */
class KestraTestListener implements TestListener, TestOutputListener {

    private final Provider<LoggerService> service
    private final String taskPath
    private final String moduleName
    private final List<String> testSourceRoots
    private final Map<TestDescriptor, Object> ids = new ConcurrentHashMap<>()

    KestraTestListener(Provider<LoggerService> service, String taskPath, String moduleName, List<String> testSourceRoots) {
        this.service = service
        this.taskPath = taskPath
        this.moduleName = moduleName
        this.testSourceRoots = testSourceRoots
    }

    @Override
    void beforeSuite(TestDescriptor suite) {
        // no-op: only individual tests and the root (task-level) suite are interesting, and the
        // root suite is only meaningful once it has a result, i.e. in afterSuite.
    }

    @Override
    void afterSuite(TestDescriptor suite, TestResult result) {
        if (suite.parent == null) {
            service.get().taskTestsFinished(taskPath, moduleName, result)
        }
    }

    @Override
    void beforeTest(TestDescriptor testDescriptor) {
        Object id = service.get().testStarted(taskPath, moduleName, testDescriptor)
        if (id != null) ids[testDescriptor] = id
    }

    @Override
    void afterTest(TestDescriptor testDescriptor, TestResult result) {
        Object id = ids.remove(testDescriptor)
        service.get().testFinished(id, taskPath, moduleName, testDescriptor, result, testSourceRoots)
    }

    @Override
    void onOutput(TestDescriptor testDescriptor, TestOutputEvent outputEvent) {
        Object id = ids[testDescriptor]
        if (id != null) service.get().testOutput(id, outputEvent)
    }
}
