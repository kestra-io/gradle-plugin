package io.kestra.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * End-to-end tests via Gradle TestKit. Each test creates a minimal multi-project build with
 * 'heavy1', 'heavy2' (priority) and several 'light*' subprojects. Test tasks write timing
 * events to a shared file so we can verify concurrency limits without real tests.
 */
class TestSchedulingPluginTest {

    @TempDir
    Path testProjectDir

    @BeforeEach
    void setup() {
        // Write a reusable 'fake test' class that records start/end nanos to a shared file.
        writeFile('buildSrc/build.gradle', "plugins { id 'groovy' }")
        writeFile('buildSrc/settings.gradle', "rootProject.name = 'buildSrc'")
    }

    private File file(String relative) {
        File f = testProjectDir.resolve(relative).toFile()
        f.parentFile.mkdirs()
        f
    }

    private void writeFile(String relative, String content) {
        file(relative).text = content
    }

    // Creates a settings.gradle applying the plugin with the given extension block.
    private void writeSettings(List<String> subprojects = ['heavy1', 'heavy2', 'light1', 'light2', 'light3', 'light4'],
                               String extensionBlock = "priority = [':heavy1', ':heavy2']") {
        String includeList = subprojects.collect { "'${it}'" }.join(', ')
        writeFile('settings.gradle', """
            pluginManagement { }
            plugins {
                id 'io.kestra.gradle.test-scheduling'
            }
            rootProject.name = 'ts-test'
            include ${includeList}
            kestraTestScheduling {
                ${extensionBlock}
            }
        """.stripIndent())
    }

    // Creates a subproject with a fake Test task that sleeps to simulate duration.
    // dependsOnProjects wires a project dependency, so this project's test cannot start before
    // those projects are compiled and jarred. compileSleepMs widens the compile step so scheduling
    // waves are distinguishable in timing.txt.
    private void writeSubproject(String name, int sleepMs = 200, List<String> dependsOnProjects = [], int compileSleepMs = 0) {
        String deps = dependsOnProjects.collect { "implementation project(':${it}')" }.join('\n                ')
        writeFile("${name}/build.gradle", """
            plugins { id 'java' }
            dependencies {
                ${deps}
            }
            tasks.named('compileJava') {
                doFirst {
                    def f = rootProject.file('timing.txt')
                    synchronized (f) { f << "compile:${name}:\${System.nanoTime()}\\n" }
                    Thread.sleep(${compileSleepMs})
                }
            }
            test {
                doFirst {
                    def f = rootProject.file('timing.txt')
                    synchronized (f) { f << "start:${name}:\${System.nanoTime()}\\n" }
                }
                doLast {
                    def f = rootProject.file('timing.txt')
                    synchronized (f) { f << "end:${name}:\${System.nanoTime()}\\n" }
                }
            }
        """.stripIndent())
        // A main class so compileJava has sources — without it the task is NO-SOURCE and never runs.
        writeFile("${name}/src/main/java/${name.capitalize()}Main.java", """
            public class ${name.capitalize()}Main { public static String hello() { return "${name}"; } }
        """.stripIndent())
        // A do-nothing test class so Gradle's Test task actually executes.
        writeFile("${name}/src/test/java/Placeholder.java", """
            import org.junit.jupiter.api.Test;
            public class Placeholder { @Test public void ok() {} }
        """.stripIndent())
    }

    private void writeRootBuildGradle() {
        writeFile('build.gradle', """
            subprojects {
                repositories { mavenCentral() }
                plugins.withId('java') {
                    dependencies {
                        testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                        testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
                    }
                    test { useJUnitPlatform() }
                }
            }
        """.stripIndent())
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments(args)
            .forwardOutput()
    }

    @Test
    void 'plugin registers and logs armed summary'() {
        writeSettings()
        writeRootBuildGradle()
        ['heavy1', 'heavy2', 'light1', 'light2', 'light3', 'light4'].each { writeSubproject(it) }

        def result = runner('test', '--parallel', '--max-workers=4', '--info').build()
        assertTrue(result.output.contains('[test-scheduling]'),
            'Expected plugin log line to appear in output')
    }

    @Test
    void 'tasks are registered and succeed'() {
        def projects = ['heavy1', 'heavy2', 'light1', 'light2']
        writeSettings(projects)
        writeRootBuildGradle()
        projects.each { writeSubproject(it) }

        def result = runner('test', '--parallel', '--max-workers=4').build()
        projects.each { name ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":${name}:test")?.outcome,
                "Expected :${name}:test to succeed")
        }
    }

    @Test
    void 'plugin is a no-op when max-workers=1'() {
        def projects = ['heavy1', 'heavy2', 'light1', 'light2']
        writeSettings(projects)
        writeRootBuildGradle()
        projects.each { writeSubproject(it) }

        // Must not hang or fail.
        def result = runner('test', '--max-workers=1').build()
        def allowed = [TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE, TaskOutcome.SKIPPED, TaskOutcome.NO_SOURCE, TaskOutcome.FROM_CACHE]
        assertTrue(result.tasks.every { it.outcome in allowed },
            "Unexpected outcomes: ${result.tasks.findAll { !(it.outcome in allowed) }}")
    }

    @Test
    void 'excluding all priority tasks does not leave light tasks permanently capped'() {
        writeSettings()
        writeRootBuildGradle()
        ['heavy1', 'heavy2', 'light1', 'light2', 'light3', 'light4'].each { writeSubproject(it) }

        // Exclude both priority tasks: no priority tasks in graph -> service not armed -> no cap.
        def result = runner('test', '--parallel', '--max-workers=4',
            '-x', ':heavy1:test', '-x', ':heavy2:test').build()
        ['light1', 'light2', 'light3', 'light4'].each { name ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":${name}:test")?.outcome,
                "Expected :${name}:test to succeed when priority tasks excluded")
        }
    }

    @Test
    void 'enabled=false runs all tasks without throttling'() {
        def projects = ['heavy1', 'heavy2', 'light1', 'light2']
        writeSettings(projects, "priority = [':heavy1', ':heavy2']; enabled = false")
        writeRootBuildGradle()
        projects.each { writeSubproject(it) }

        def result = runner('test', '--parallel', '--max-workers=4').build()
        assertFalse(result.output.contains('Armed'),
            'Expected no Armed log when plugin is disabled')
        assertTrue(result.tasks.findAll { it.outcome == TaskOutcome.SUCCESS }.size() >= 2)
    }

    @Test
    void 'running only a priority task path does not arm the service'() {
        def projects = ['heavy1', 'heavy2', 'light1', 'light2']
        writeSettings(projects)
        writeRootBuildGradle()
        projects.each { writeSubproject(it) }

        // Only priority in the graph -> zero non-priority test tasks -> not armed.
        def result = runner(':heavy1:test', '--parallel', '--max-workers=4').build()
        assertFalse(result.output.contains('Armed'),
            'Service must not arm when no non-priority tasks are in the graph')
        assertEquals(TaskOutcome.SUCCESS, result.task(':heavy1:test')?.outcome)
    }

    /** Reads timing.txt and returns the module names in the order their compileJava started. */
    private List<String> compileOrder() {
        file('timing.txt').readLines()
            .findAll { it.startsWith('compile:') }
            .collect { it.split(':')[1] }
    }

    // Gradle expands a lifecycle task name over projects in alphabetical order, so 'zheavy' and its
    // upstream 'zupstream' land at the tail of the plan by default — the starvation this plugin fixes.
    private static final List<String> LIGHT = ['light1', 'light2', 'light3', 'light4', 'light5']

    private void writeChainBuild(String extensionBlock = "priority = [':zheavy']") {
        writeSettings(LIGHT + ['zheavy', 'zupstream'], extensionBlock)
        writeRootBuildGradle()
        LIGHT.each { writeSubproject(it, 200, [], 400) }
        writeSubproject('zupstream', 200, [], 400)
        writeSubproject('zheavy', 200, ['zupstream'], 400)
    }

    @Test
    void 'priority compile chain is scheduled in the first wave'() {
        writeChainBuild()

        def result = runner('test', '--parallel', '--max-workers=2').build()

        assertEquals(TaskOutcome.SUCCESS, result.task(':zheavy:test')?.outcome)
        assertTrue(result.output.contains('[test-scheduling] Prioritised in the execution plan: :zheavy:test'),
            "Expected the plan to be reordered, got: ${result.output}")
        def order = compileOrder()
        assertFalse(order.isEmpty(), 'No compile timings recorded — fixture is broken')
        assertTrue(order.indexOf('zupstream') < 2,
            "Expected :zupstream (zheavy's compile chain) in the first scheduling wave, got: ${order}")
    }

    @Test
    void 'preferPriorityFirst=false leaves the chain at the tail of the plan'() {
        writeChainBuild("priority = [':zheavy']; preferPriorityFirst = false")

        def result = runner('test', '--parallel', '--max-workers=2').build()

        assertFalse(result.output.contains('Prioritised in the execution plan'),
            'Plan must not be reordered when preferPriorityFirst is false')
        def order = compileOrder()
        assertFalse(order.isEmpty(), 'No compile timings recorded — fixture is broken')
        assertTrue(order.indexOf('zupstream') >= 2,
            "Expected :zupstream to be scheduled late without the fix, got: ${order}")
    }

    @Test
    void 'task options suppress the plan reordering'() {
        writeChainBuild()

        // --tests binds to the task name it follows; prepending :zheavy:test would run it unfiltered.
        def result = runner('test', '--tests', 'Placeholder', '--parallel', '--max-workers=2').build()

        assertFalse(result.output.contains('Prioritised in the execution plan'),
            'Plan must not be reordered when task options are present')
        assertEquals(TaskOutcome.SUCCESS, result.task(':zheavy:test')?.outcome)
    }

    @Test
    void 'excluded priority tasks are not re-added by the reordering'() {
        writeChainBuild()

        def result = runner('test', '-x', ':zheavy:test', '--parallel', '--max-workers=2').build()

        assertFalse(result.output.contains('Prioritised in the execution plan'),
            'An excluded priority task must not be prepended to the requested tasks')
        assertNull(result.task(':zheavy:test'))
    }
}
