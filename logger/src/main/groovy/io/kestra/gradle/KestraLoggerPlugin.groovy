package io.kestra.gradle

import io.kestra.gradle.logger.HeapUsage
import io.kestra.gradle.logger.KestraTestListener
import io.kestra.gradle.logger.LoggerService
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.build.event.BuildEventsListenerRegistry

import javax.inject.Inject

/**
 * Opinionated console output for parallel multi-module CI builds. Applied from settings.gradle so
 * it covers every project from one place, the same way test-scheduling does.
 *
 * Two independent mechanisms, both driven entirely through public Gradle API — no internal
 * classes, no reflection:
 * <ol>
 *   <li>A "[module:task] outcome duration" line for every task, via
 *       {@code BuildEventsListenerRegistry.onTaskCompletion} (Tooling API events, the same public
 *       hook test-scheduling already uses to release worker-slot permits).</li>
 *   <li>A full replacement for {@code com.adarshr.test-logger}'s per-test rendering, driven by the
 *       plain {@code Test.addTestListener}/{@code addTestOutputListener} API, with every line
 *       (including a failing test's stack trace) carrying the module, JUnit namespace, class name
 *       and parameters — and a heartbeat for tests that are still running, since JUnit only reports
 *       a test once it finishes.</li>
 * </ol>
 *
 * Both mechanisms print through a single shared {@link LoggerService} so that a failing test's
 * multi-line block is atomic under {@code --parallel}: two modules' output can never interleave
 * mid-block.
 *
 * An earlier design also tried to prefix every raw line of Gradle's own console output (so a javac
 * warning would carry the same "[module:task]" prefix). That required reflectively replacing
 * {@code OutputEventRenderer}'s internal listener chain, which reliably deadlocked the Gradle
 * client/daemon shutdown handshake in testing — reproduced under both {@code --no-daemon} and a
 * persistent daemon. That approach was dropped; this plugin never touches Gradle-internal classes.
 */
abstract class KestraLoggerPlugin implements Plugin<Settings> {

    @Inject
    abstract BuildEventsListenerRegistry getEventsListenerRegistry()

    @Override
    void apply(Settings settings) {
        def extension = settings.extensions.create('kestraLogger', KestraLoggerExtension)

        Provider<LoggerService> serviceProvider = settings.gradle.sharedServices
            .registerIfAbsent('kestraLoggerService', LoggerService) { }

        eventsListenerRegistry.onTaskCompletion(serviceProvider)

        // Configure the service once the whole settings file (including any kestraLogger { } block
        // appearing after the plugins {} block) has been evaluated. Always called, even when
        // extension.enabled is false -- configure() folds that flag into every sub-feature so the
        // service stays fully silent, rather than falling back to its (all-on) field defaults.
        settings.gradle.settingsEvaluated {
            serviceProvider.get().configure(extension, settings.rootProject.name)
        }

        settings.gradle.allprojects { Project project ->
            project.pluginManager.withPlugin('com.adarshr.test-logger') {
                if (extension.failOnConflictingTestLogger) {
                    throw new GradleException(
                        "io.kestra.gradle.logger replaces com.adarshr.test-logger and both are applied to '${project.path}'. " +
                        "Remove `id 'com.adarshr.test-logger'` and its `testlogger { }` block -- the equivalent settings live " +
                        "under kestraLogger.test { }: slowThreshold, showExceptions, showFullStackTraces, showCauses, " +
                        "showPassedStandardStreams, showSkippedStandardStreams. To keep both anyway, set " +
                        "kestraLogger.failOnConflictingTestLogger = false."
                    )
                }
            }

            // "Started" line for every task -- BuildEventsListenerRegistry only exposes finish
            // events (OperationCompletionListener has no onStart), so a start line needs doFirst,
            // which only fires when the task's actions actually run (never for UP-TO-DATE/skipped).
            project.tasks.configureEach { Task task ->
                if (!extension.enabled) return
                task.doFirst {
                    serviceProvider.get().taskStarted(task.path)
                }
            }

            project.tasks.withType(Test).configureEach { Test task ->
                task.usesService(serviceProvider)
                if (!extension.enabled) return

                // Silence Gradle's own per-event test logging so exactly one renderer owns this output.
                task.testLogging { it.events = [] }

                String moduleName = KestraLoggerPlugin.moduleNameOf(project)
                List<String> testSourceRoots = KestraLoggerPlugin.testSourceRootsOf(project)
                def listener = new KestraTestListener(serviceProvider, task.path, moduleName, testSourceRoots)
                task.addTestListener(listener)
                task.addTestOutputListener(listener)

                if (extension.test.heap.enabled) {
                    File gcLogDir = new File(project.layout.buildDirectory.get().asFile, "kestra-logger/gc-logs/${task.name}")
                    task.jvmArgs += [HeapUsage.jvmLoggingArg(gcLogDir)]
                    // Registering (and wiping/recreating the dir) from doFirst -- rather than doLast --
                    // is what makes heap capture survive a failing test: doFirst always runs when a
                    // task genuinely executes this build, whereas doLast is skipped once any action in
                    // the task throws. The actual read-back happens later still, from the
                    // BuildEventsListenerRegistry completion hook, which fires even on failure.
                    task.doFirst {
                        gcLogDir.deleteDir()
                        gcLogDir.mkdirs()
                        serviceProvider.get().registerHeapLogDir(task.path, gcLogDir)
                    }
                }
            }
        }
    }

    private static String moduleNameOf(Project project) {
        String path = project.path.replaceFirst('^:', '')
        return path.isEmpty() ? project.name : path
    }

    private static List<String> testSourceRootsOf(Project project) {
        JavaPluginExtension java = project.extensions.findByType(JavaPluginExtension)
        if (java == null) return []
        def testSourceSet = java.sourceSets.findByName('test')
        return testSourceSet ? testSourceSet.allJava.srcDirs.collect { it.absolutePath } : []
    }
}
