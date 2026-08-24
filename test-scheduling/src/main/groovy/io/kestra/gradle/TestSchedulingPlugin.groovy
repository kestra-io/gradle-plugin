package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.testing.Test
import org.gradle.build.event.BuildEventsListenerRegistry

import javax.inject.Inject

/**
 * Reserves worker slots for high-priority test modules so they are not starved by light modules
 * that happen to become dependency-ready first.
 *
 * Applies from settings.gradle:
 * <pre>
 *   plugins { id 'io.kestra.gradle.test-scheduling' version '...' }
 *   kestraTestScheduling {
 *       priority = [':core', ':tests']
 *   }
 * </pre>
 *
 * Two independent mechanisms:
 * <ol>
 *   <li>Plan ordering — the priority tasks are moved to the head of the requested task list, which
 *       inserts them (and their whole dependency chain: their own compile/jar tasks plus those of
 *       every upstream project) at the head of the execution plan. Gradle picks ready nodes in plan
 *       order, so the chain a priority test needs gets workers before light-module work does.
 *       Without this the reservation below is useless: the priority tests are not dependency-ready
 *       yet and the reserved slots sit idle while their compile chain queues behind light modules.</li>
 *   <li>Slot reservation — while any priority Test task is running, non-priority Test tasks share
 *       (maxWorkers - reserved) worker slots. Once all priority tasks finish the reserved slots are
 *       released and non-priority tasks expand to the full worker count.</li>
 * </ol>
 *
 * Requires --parallel and --max-workers > 1. Falls back to no throttling when running with a
 * single worker, when the graph contains no priority tasks, or when priority tasks are excluded
 * (e.g. -x :core:test). Safe with --continue.
 *
 * Configuration cache: on a cache hit the plugin is not configured and the service is never armed,
 * so the build runs unthrottled — a safe degradation, not incorrect behaviour.
 */
abstract class TestSchedulingPlugin implements Plugin<Settings> {

    @Inject
    abstract BuildEventsListenerRegistry getEventsListenerRegistry()

    @Override
    void apply(Settings settings) {
        def extension = settings.extensions.create('kestraTestScheduling', TestSchedulingExtension)

        def serviceProvider = settings.gradle.sharedServices
            .registerIfAbsent('kestraTestSlots', TestSlotService) { }

        eventsListenerRegistry.onTaskCompletion(serviceProvider)

        // Obtain WorkerLeaseService defensively — it is internal Gradle API.
        // We defer resolution until the root project is available.
        def leaseServiceHolder = new Object[1]
        settings.gradle.rootProject { rootProject ->
            try {
                def projectInternal = rootProject as groovy.lang.GroovyObject
                def services = projectInternal.invokeMethod('getServices', null)
                def workerLeaseServiceClass = Class.forName(
                    'org.gradle.internal.work.WorkerLeaseService',
                    false,
                    rootProject.class.classLoader
                )
                leaseServiceHolder[0] = services.invokeMethod('get', [workerLeaseServiceClass] as Object[])
            } catch (Throwable ignored) {
                rootProject.logger.info('[test-scheduling] WorkerLeaseService unavailable; light tasks will block without yielding their worker lease')
            }
        }

        // Wire usesService and doFirst acquire for every non-priority Test task.
        settings.gradle.allprojects { project ->
            project.tasks.withType(Test).configureEach { Test t ->
                t.usesService(serviceProvider)

                boolean isPriority = TestSchedulingPlugin.isPriorityTask(t.project.path, extension)
                if (!isPriority) {
                    t.doFirst {
                        serviceProvider.get().acquire(t.path)
                    }
                }
            }
        }

        // Put the priority tasks at the head of the execution plan. This must happen before the plan
        // is built: ordering rules added from taskGraph.whenReady are silently ignored, and
        // shouldRunAfter is not honoured by the parallel scheduler at all.
        settings.gradle.projectsEvaluated { Gradle gradle ->
            if (!extension.enabled || !extension.preferPriorityFirst) return
            try {
                TestSchedulingPlugin.prioritiseInExecutionPlan(gradle, extension)
            } catch (Throwable e) {
                gradle.rootProject.logger.info(
                    '[test-scheduling] Could not prioritise the execution plan: {}', e.toString())
            }
        }

        // Compute the exact plan from the real task graph and arm the service.
        settings.gradle.taskGraph.whenReady { graph ->
            if (!extension.enabled) return

            int maxWorkers = settings.gradle.startParameter.maxWorkerCount
            if (maxWorkers <= 1) return

            // Collect priority Test tasks actually present in the graph.
            def priorityTestTasks = graph.allTasks.findAll { task ->
                task instanceof Test && TestSchedulingPlugin.isPriorityTask(task.project.path, extension)
            }
            if (priorityTestTasks.isEmpty()) return

            def nonPriorityTestTasks = graph.allTasks.findAll { task ->
                task instanceof Test && !TestSchedulingPlugin.isPriorityTask(task.project.path, extension)
            }
            if (nonPriorityTestTasks.isEmpty()) return

            def priorityTaskPaths = priorityTestTasks*.path as Set<String>

            int reserved = extension.reservedSlots != null
                ? extension.reservedSlots
                : Math.min(priorityTestTasks.size(), maxWorkers - 1)
            int limited = Math.max(1, maxWorkers - reserved)

            settings.gradle.rootProject { rootProject ->
                rootProject.logger.lifecycle(
                    "[test-scheduling] Armed: {} priority task(s), {} reserved slot(s), {} light-task slot(s) while priority tasks run",
                    priorityTestTasks.size(), reserved, limited
                )
            }

            serviceProvider.get().arm(priorityTaskPaths, limited, reserved, leaseServiceHolder[0])
        }
    }

    /**
     * Moves the priority projects' copies of every requested lifecycle task to the front of
     * {@code startParameter.taskNames}. Gradle builds the execution plan by walking the requested
     * tasks in order and inserting each one's dependencies before it, then picks ready nodes in that
     * order — so this pulls the priority modules' compile chain into the first scheduling wave.
     *
     * Runs from projectsEvaluated so tasks can be checked for existence, which is still before the
     * plan is built. No-op for invocations we cannot safely rewrite.
     */
    private static void prioritiseInExecutionPlan(Gradle gradle, TestSchedulingExtension ext) {
        def startParameter = gradle.startParameter
        List<String> requested = new ArrayList<>(startParameter.taskNames)

        // Nothing requested: Gradle runs the default tasks and there is no list to reorder.
        if (requested.isEmpty()) return
        // Task-level options (--tests, --rerun, …) bind to the task name they follow. Prepending a
        // task in front of them would run the priority module unfiltered, so leave those alone.
        if (requested.any { it.startsWith('-') }) return

        Set<String> excluded = startParameter.excludedTaskNames as Set<String>
        List<String> prepend = []

        requested.findAll { !it.contains(':') }.each { String name ->
            ext.priority.each { String declared ->
                String projectPath = declared.startsWith(':') ? declared : ":${declared}"
                String taskPath = (projectPath == ':' ? '' : projectPath) + ':' + name

                if (name in excluded || taskPath in excluded || taskPath.substring(1) in excluded) return
                if (taskPath in requested || taskPath in prepend) return

                def project = gradle.rootProject.findProject(projectPath)
                if (project == null || project.tasks.findByName(name) == null) return

                prepend << taskPath
            }
        }
        if (prepend.isEmpty()) return

        startParameter.setTaskNames(prepend + requested)
        gradle.rootProject.logger.lifecycle(
            '[test-scheduling] Prioritised in the execution plan: {}', prepend.join(', '))
    }

    private static boolean isPriorityTask(String projectPath, TestSchedulingExtension ext) {
        ext.priority.any { declared ->
            String normalised = declared.startsWith(':') ? declared : ":${declared}"
            projectPath == normalised
        }
    }
}
