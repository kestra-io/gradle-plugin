package io.kestra.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * End-to-end tests via Gradle TestKit. Each test creates a minimal multi-project build so prefix
 * attribution and atomic-block rendering can be checked under real --parallel execution.
 */
class KestraLoggerPluginTest {

    @org.junit.jupiter.api.io.TempDir
    Path testProjectDir

    private File file(String relative) {
        File f = testProjectDir.resolve(relative).toFile()
        f.parentFile.mkdirs()
        f
    }

    private void writeFile(String relative, String content) {
        file(relative).text = content
    }

    private void writeSettings(List<String> modules, String extensionBlock = '') {
        String includeList = modules.collect { "'${it}'" }.join(', ')
        writeFile('settings.gradle', """
            pluginManagement { }
            plugins {
                id 'io.kestra.gradle.logger'
            }
            rootProject.name = 'logger-test'
            include ${includeList}
            kestraLogger {
                ${extensionBlock}
            }
        """.stripIndent())
    }

    private void writeModuleBuild(String name) {
        writeFile("${name}/build.gradle", """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }
            test { useJUnitPlatform() }
        """.stripIndent())
    }

    // A test class with one passing, one failing, one skipped and one deliberately slow test.
    private void writeSampleTest(String moduleName, int slowSleepMs = 0) {
        writeFile("${moduleName}/src/test/java/io/kestra/sample/SampleTest.java", """
            package io.kestra.sample;

            import org.junit.jupiter.api.Disabled;
            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.fail;

            public class SampleTest {
                @Test
                void passes() {
                    assertEquals(2, 1 + 1);
                }

                @Test
                void fails() {
                    fail("expected failure for test coverage");
                }

                @Test
                @Disabled("intentionally skipped for test coverage")
                void skipped() {
                }

                @Test
                void slow() throws InterruptedException {
                    Thread.sleep(${slowSleepMs});
                }
            }
        """.stripIndent())
    }

    private GradleRunner runner(Map<String, String> env = [:], String... args) {
        GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments(args)
            .withEnvironment(['NO_COLOR': '1'] + env)
            .forwardOutput()
    }

    @Test
    void 'passing test prints exactly one quiet line'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() { System.out.println("should not appear"); }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertTrue(result.output.contains('[modA:test] '), "Expected an unpadded, untruncated modA:test prefix, got:\n${result.output}")
        assertTrue(result.output.contains('✔'), 'Expected a pass icon')
        assertFalse(result.output.contains('should not appear'),
            'Passing test stdout must be hidden by default (showPassedStandardStreams=false)')
    }

    @Test
    void 'showPassedStandardError defaults to true, printing a passing test stderr but keeping its stdout hidden'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() {
                    System.out.println("stdout should stay hidden");
                    System.err.println("stderr should be shown");
                }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertTrue(result.output.contains('stderr should be shown'),
            'showPassedStandardError defaults to true, so a passing test\'s buffered stderr must print')
        assertFalse(result.output.contains('stdout should stay hidden'),
            'showPassedStandardError must not print a passing test\'s stdout')
    }

    @Test
    void 'showPassedStandardError=false hides a passing test stderr too'() {
        writeSettings(['modA'], 'test { showPassedStandardError = false }')
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() {
                    System.err.println("stderr should stay hidden");
                }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertFalse(result.output.contains('stderr should stay hidden'),
            'showPassedStandardError=false must hide a passing test\'s buffered stderr')
    }

    @Test
    void 'failing test block carries full coordinates on every line'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeSampleTest('modA', 10)

        BuildResult result = runner('test', '--continue').buildAndFail()

        String[] lines = result.output.split('\n')
        // Restrict to the rendered block (prefixed lines), excluding the plain build-end summary listing.
        List<String> failureBlock = lines.findAll { it.contains('[modA:test') && it.contains('SampleTest › fails') }
        assertTrue(failureBlock.size() >= 2, "Expected a multi-line failure block, got:\n${result.output}")
        failureBlock.each { line ->
            assertTrue(line.contains('i.k.sample › SampleTest › fails'), "Every failure line must carry full test coordinates: ${line}")
        }
        assertTrue(result.output.contains('AssertionFailedError') || result.output.contains('AssertionError'),
            'Expected the exception type in the failure block')
        assertFalse(result.output.contains('⟩'), 'Log output lines must use │, never ⟩')
        assertTrue(failureBlock.any { it.contains('│') }, 'Expected stack trace continuation lines to carry │')
    }

    @Test
    void 'skipped test shows a single line'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeSampleTest('modA', 10)

        BuildResult result = runner('test', '--continue').buildAndFail()

        assertTrue(result.output.contains('i.k.sample › SampleTest › skipped'), 'Expected the skipped test to be reported')
    }

    @Test
    void 'per-task summary line reports totals'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeSampleTest('modA', 10)

        BuildResult result = runner('test', '--continue').buildAndFail()

        assertTrue(result.output.contains('4 total · 2 passed · 1 failed · 1 skipped in '),
            "Expected a task-level totals line with the new wording, got:\n${result.output}")
    }

    @Test
    void 'build-end summary reports aggregate totals and failed tests'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeSampleTest('modA', 10)

        BuildResult result = runner('test', '--continue').buildAndFail()

        assertTrue(result.output.contains('[test:summary] ═ 4 total · 2 passed · 1 failed · 1 skipped › '),
            "Expected the aggregate summary line, got:\n${result.output}")
        assertTrue(result.output.contains('[test:summary] ◆ Failed tests:'),
            "Expected the 'Failed tests:' header, got:\n${result.output}")
        assertTrue(result.output.contains('[test:summary] │ modA › i.k.sample › SampleTest › fails() '),
            "Expected the failed test entry with its duration, got:\n${result.output}")
    }

    @Test
    void 'task lines report outcome and duration for non-test tasks'() {
        writeSettings(['modA'])
        writeModuleBuild('modA')
        writeFile('modA/src/main/java/io/kestra/sample/Main.java', '''
            package io.kestra.sample;
            public class Main { public static void main(String[] a) {} }
        '''.stripIndent())

        BuildResult result = runner('compileJava').build()

        assertTrue(result.output.contains('[modA:compileJava'), "Expected a compileJava task line, got:\n${result.output}")
        assertTrue(result.output.contains('SUCCESS'), 'Expected the SUCCESS outcome')
    }

    @Test
    void 'heartbeat announces a still-running test'() {
        writeSettings(['modA'], 'test { slowThreshold = 100000; heartbeat { threshold = 200; interval = 200 } }')
        writeModuleBuild('modA')
        // The ticker polls once per second, anchored from configure() (settings evaluation) --
        // well before :modA:test itself starts running several tasks later -- so the alignment
        // between "test starts" and "next tick" is effectively random within that 1s window.
        // The sleep needs real margin over 1000ms so a tick reliably lands while it's still running.
        writeSampleTest('modA', 1800)

        BuildResult result = runner('test', '--continue', '--tests', 'io.kestra.sample.SampleTest.slow').build()

        assertTrue(result.output.contains('⏱'), "Expected at least one heartbeat line, got:\n${result.output}")
        assertTrue(result.output.contains('i.k.sample › SampleTest › slow() running since '),
            "Expected the 'running since' wording, got:\n${result.output}")
    }

    @Test
    void 'conflicting test-logger plugin fails the build with a clear message'() {
        writeFile('buildSrc/build.gradle', "plugins { id 'groovy' }")
        writeFile('buildSrc/settings.gradle', "rootProject.name = 'buildSrc'")
        writeFile('buildSrc/src/main/groovy/FakeTestLoggerPlugin.groovy', '''
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            class FakeTestLoggerPlugin implements Plugin<Project> {
                void apply(Project project) { }
            }
        '''.stripIndent())
        writeFile('buildSrc/src/main/resources/META-INF/gradle-plugins/com.adarshr.test-logger.properties',
            'implementation-class=FakeTestLoggerPlugin\n')

        writeSettings(['modA'])
        writeFile('modA/build.gradle', """
            plugins { id 'java'; id 'com.adarshr.test-logger' }
            repositories { mavenCentral() }
        """.stripIndent())

        BuildResult result = runner('help').buildAndFail()

        assertTrue(result.output.contains('io.kestra.gradle.logger replaces com.adarshr.test-logger'),
            "Expected a conflict error message, got:\n${result.output}")
    }

    @Test
    void 'failOnConflictingTestLogger disabled allows both plugins'() {
        writeFile('buildSrc/build.gradle', "plugins { id 'groovy' }")
        writeFile('buildSrc/settings.gradle', "rootProject.name = 'buildSrc'")
        writeFile('buildSrc/src/main/groovy/FakeTestLoggerPlugin.groovy', '''
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            class FakeTestLoggerPlugin implements Plugin<Project> {
                void apply(Project project) { }
            }
        '''.stripIndent())
        writeFile('buildSrc/src/main/resources/META-INF/gradle-plugins/com.adarshr.test-logger.properties',
            'implementation-class=FakeTestLoggerPlugin\n')

        writeSettings(['modA'], 'failOnConflictingTestLogger = false')
        writeFile('modA/build.gradle', """
            plugins { id 'java'; id 'com.adarshr.test-logger' }
            repositories { mavenCentral() }
        """.stripIndent())

        BuildResult result = runner('help').build()

        assertFalse(result.output.contains('replaces com.adarshr.test-logger'))
    }

    @Test
    void 'heap enabled wires GC logging and reports peak heap'() {
        writeSettings(['modA'], 'test { heap { enabled = true } }')
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() { }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        File gcLogDir = testProjectDir.resolve('modA/build/kestra-logger/gc-logs/test').toFile()
        File[] logs = gcLogDir.listFiles { File f -> f.name.startsWith('gc-') && f.name.endsWith('.log') }
        assertTrue(logs != null && logs.length > 0,
            "Expected at least one gc-*.log file wired by the heap jvmArg, got: ${gcLogDir}")
        assertTrue(result.output.contains('peak heap'), "Expected a peak heap segment in the summary, got:\n${result.output}")
    }

    @Test
    void 'heap enabled reports a real used-over-capacity percentage when a GC actually fires'() {
        writeSettings(['modA'], 'test { heap { enabled = true } }')
        writeFile('modA/build.gradle', """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }
            test {
                useJUnitPlatform()
                maxHeapSize = '32m'
            }
        """.stripIndent())
        // A tiny heap plus a lot of short-lived garbage reliably forces at least one young GC, unlike
        // a trivial test that finishes before the JVM ever collects.
        writeFile('modA/src/test/java/io/kestra/sample/AllocatingTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            import java.util.ArrayList;
            import java.util.List;
            public class AllocatingTest {
                @Test
                void allocates() {
                    List<byte[]> garbage = new ArrayList<>();
                    for (int i = 0; i < 4000; i++) {
                        garbage.add(new byte[64 * 1024]);
                        if (garbage.size() > 100) garbage.remove(0);
                    }
                }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertTrue((result.output =~ /peak heap \d+(\.\d+)? [KMGT]?B \/ \d+(\.\d+)? [KMGT]?B \(\d+%\)/).find(),
            "Expected a real used/capacity/percent heap line, got:\n${result.output}")
    }

    @Test
    void 'heap usage is still captured when the Test task itself fails'() {
        writeSettings(['modA'], 'test { heap { enabled = true } }')
        writeFile('modA/build.gradle', """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }
            test {
                useJUnitPlatform()
                maxHeapSize = '32m'
            }
        """.stripIndent())
        // doLast is skipped once a task action throws -- this is exactly the workload+failure
        // combination that silently dropped heap capture before processHeapUsage moved to the
        // BuildEventsListenerRegistry completion hook, which fires on failure too.
        writeFile('modA/src/test/java/io/kestra/sample/FailingAllocatingTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            import java.util.ArrayList;
            import java.util.List;
            import static org.junit.jupiter.api.Assertions.fail;
            public class FailingAllocatingTest {
                @Test
                void allocatesThenFails() {
                    List<byte[]> garbage = new ArrayList<>();
                    for (int i = 0; i < 4000; i++) {
                        garbage.add(new byte[64 * 1024]);
                        if (garbage.size() > 100) garbage.remove(0);
                    }
                    fail("expected failure for test coverage");
                }
            }
        '''.stripIndent())

        BuildResult result = runner('test').buildAndFail()

        assertTrue((result.output =~ /peak heap \d+(\.\d+)? [KMGT]?B \/ \d+(\.\d+)? [KMGT]?B \(\d+%\)/).find(),
            "Expected heap data even though the Test task failed, got:\n${result.output}")
    }

    @Test
    void 'heap disabled skips GC logging and the peak heap segment'() {
        writeSettings(['modA'], 'test { heap { enabled = false } }')
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() { }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        File gcLogDir = testProjectDir.resolve('modA/build/kestra-logger/gc-logs/test').toFile()
        assertFalse(gcLogDir.exists(), 'Expected no gc-logs directory when heap.enabled = false')
        assertFalse(result.output.contains('peak heap'), 'Expected no peak heap segment when heap.enabled = false')
    }

    @Test
    void 'heap interval prints a current heap line while a test is still running'() {
        writeSettings(['modA'], 'test { heap { enabled = true; interval = 300 } }')
        writeFile('modA/build.gradle', """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }
            test {
                useJUnitPlatform()
                maxHeapSize = '32m'
            }
        """.stripIndent())
        // Long enough, and spread across enough sleeps, that at least one 1-second tick lands
        // more than interval=300ms after the task started while this test is still running.
        writeFile('modA/src/test/java/io/kestra/sample/SlowAllocatingTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            import java.util.ArrayList;
            import java.util.List;
            public class SlowAllocatingTest {
                @Test
                void allocatesSlowly() throws InterruptedException {
                    List<byte[]> garbage = new ArrayList<>();
                    for (int i = 0; i < 4000; i++) {
                        garbage.add(new byte[64 * 1024]);
                        if (garbage.size() > 100) garbage.remove(0);
                        if (i % 100 == 0) Thread.sleep(50);
                    }
                }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertTrue(result.output.contains('current heap'),
            "Expected at least one current-heap line while the test was still running, got:\n${result.output}")
    }

    @Test
    void 'enabled false disables all rendering'() {
        writeSettings(['modA'], 'enabled = false')
        writeModuleBuild('modA')
        writeFile('modA/src/test/java/io/kestra/sample/PassOnlyTest.java', '''
            package io.kestra.sample;
            import org.junit.jupiter.api.Test;
            public class PassOnlyTest {
                @Test void ok() { }
            }
        '''.stripIndent())

        BuildResult result = runner('test').build()

        assertFalse(result.output.contains('[modA:test'), 'Expected no plugin output when disabled')
    }
}
