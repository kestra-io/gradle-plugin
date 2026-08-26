package io.kestra.gradle

/**
 * Configuration for the logger plugin.
 *
 * <pre>
 * kestraLogger {
 *     enabled = true
 *     failOnConflictingTestLogger = true   // fail the build if com.adarshr.test-logger is also applied
 *
 *     task {
 *         enabled = true
 *         colors = true
 *         skipOutcomes = ['NO-SOURCE']
 *     }
 *
 *     test {
 *         slowThreshold = 2000
 *         showExceptions = true
 *         showFullStackTraces = true
 *         showCauses = true
 *         showPassedStandardStreams = false
 *         showPassedStandardError = false
 *         showSkippedStandardStreams = true
 *         showFailedStandardStreams = true
 *         showStart = false   // true when GITHUB_ACTIONS step debug logging (RUNNER_DEBUG=1) is on
 *         fullNamespace = false
 *
 *         heartbeat {
 *             enabled = true
 *             threshold = 5000
 *             interval = 5000
 *             displayOutput = true
 *         }
 *
 *         heap {
 *             enabled = true
 *             interval = 120_000     // ms between "current heap" lines for a still-running test task
 *         }
 *     }
 *
 *     github {
 *         annotations = true
 *         jobSummary = true
 *     }
 * }
 * </pre>
 */
class KestraLoggerExtension {

    /** When false the plugin registers itself but does nothing. */
    boolean enabled = true

    /**
     * Whether applying com.adarshr.test-logger alongside this plugin is a build error. Both plugins
     * render their own competing output for the same Test tasks, so keeping both on is very likely a
     * leftover from migrating rather than something anyone wants.
     */
    boolean failOnConflictingTestLogger = true

    final TaskConfig task = new TaskConfig()
    final TestConfig test = new TestConfig()
    final GithubConfig github = new GithubConfig()

    void task(@DelegatesTo(TaskConfig) Closure closure) { configure(task, closure) }

    void test(@DelegatesTo(TestConfig) Closure closure) { configure(test, closure) }

    void github(@DelegatesTo(GithubConfig) Closure closure) { configure(github, closure) }

    private static void configure(Object target, Closure closure) {
        closure.delegate = target
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure()
    }

    /** Controls the "[module:task] outcome duration" line printed for every task, not just Test. */
    static class TaskConfig {
        /** When false, no task start/finish lines are printed (test output is unaffected). */
        boolean enabled = true
        /** Auto-disabled when NO_COLOR is set, regardless of this setting. */
        boolean colors = true
        /** Outcomes that are not worth a line — the task ran so fast it is just noise. */
        Set<String> skipOutcomes = ['NO-SOURCE'] as Set
    }

    static class TestConfig {
        /** Milliseconds at/above which a test duration renders red; half of this renders yellow. */
        int slowThreshold = 2000
        boolean showExceptions = true
        boolean showFullStackTraces = true
        boolean showCauses = true
        /** Passing tests are always a single line; this only controls their buffered stdout/stderr. */
        boolean showPassedStandardStreams = false
        /**
         * Passing tests still print their buffered stderr even though showPassedStandardStreams is
         * false -- stdout stays hidden. Defaults to true since stderr output from a passing test is
         * usually a warning worth seeing. Ignored (redundant) when showPassedStandardStreams is true,
         * since that already shows both streams.
         */
        boolean showPassedStandardError = true
        boolean showSkippedStandardStreams = true
        boolean showFailedStandardStreams = true
        /**
         * Prints a line the moment each test starts, not just when it finishes. Off by default --
         * doubles the line count -- except when GitHub Actions step debug logging is enabled
         * (https://docs.github.com/en/actions/how-tos/monitor-workflows/enable-debug-logging), which
         * sets RUNNER_DEBUG=1 and signals someone is actively diagnosing this run, e.g. a hang.
         */
        boolean showStart = System.getenv('RUNNER_DEBUG') == '1'
        /**
         * Stack trace frames whose class name starts with any of these prefixes are elided when
         * showFullStackTraces is false. Ignored when showFullStackTraces is true.
         */
        List<String> stackTraceFilters = ['org.junit.', 'jdk.internal.reflect.', 'java.lang.reflect.', 'org.gradle.']
        /**
         * When false (default), the package portion of a test's coordinates is collapsed --
         * "io.kestra.core.runners" renders as "i.k.c.runners". Set true to print it in full.
         */
        boolean fullNamespace = false

        final HeartbeatConfig heartbeat = new HeartbeatConfig()
        final HeapConfig heap = new HeapConfig()

        void heartbeat(@DelegatesTo(HeartbeatConfig) Closure closure) { configure(heartbeat, closure) }

        void heap(@DelegatesTo(HeapConfig) Closure closure) { configure(heap, closure) }
    }

    static class HeartbeatConfig {
        /** Long-running tests/tasks are invisible until they finish otherwise; this fixes that. */
        boolean enabled = true
        /** Milliseconds a test/task must run before its first heartbeat line. */
        long threshold = 5_000
        /** Milliseconds between subsequent heartbeat lines for the same still-running test/task. */
        long interval = 5_000
        /** Also flush all buffered stdout/stderr the pending test has written since the last heartbeat. */
        boolean displayOutput = true
    }

    static class HeapConfig {
        /** Actual peak JVM heap per module, sampled from worker-JVM GC logs; "n/a" if no GC occurred. */
        boolean enabled = true
        /**
         * Milliseconds between "current heap" lines printed for each still-running Test task, in
         * addition to the peak-heap line printed once the task finishes. 0 (or enabled = false)
         * disables these periodic lines.
         */
        long interval = 120_000
    }

    static class GithubConfig {
        /** ::error / ::warning workflow-command annotations for test failures. Only when GITHUB_ACTIONS=true. */
        boolean annotations = true
        /** A per-module test result table written to $GITHUB_STEP_SUMMARY. Only when GITHUB_ACTIONS=true. */
        boolean jobSummary = true
    }
}
