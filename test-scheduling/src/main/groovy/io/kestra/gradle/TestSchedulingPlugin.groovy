package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
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
 * While any priority Test task is running, non-priority Test tasks share (maxWorkers - reserved)
 * worker slots. Once all priority tasks finish the reserved slots are released and non-priority
 * tasks expand to the full worker count.
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

            // Soft ordering: prefer priority tasks to start before non-priority ones.
            if (extension.preferPriorityFirst) {
                nonPriorityTestTasks.each { nonPriorityTask ->
                    nonPriorityTask.shouldRunAfter(priorityTestTasks)
                }
            }
        }
    }

    private static boolean isPriorityTask(String projectPath, TestSchedulingExtension ext) {
        ext.priority.any { declared ->
            String normalised = declared.startsWith(':') ? declared : ":${declared}"
            projectPath == normalised
        }
    }
}
