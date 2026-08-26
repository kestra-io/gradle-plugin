# logger

Kestra builds run many modules in parallel. `com.adarshr.test-logger` renders per-test lines from
several concurrently-executing `Test` tasks into one console, so blocks interleave, failures show a
stack trace with no module attribution, and — since JUnit only reports a test once it finishes — a
test that hangs produces no output at all until it ends, which is the worst case in CI.

**Behaviour**: two mechanisms, both built entirely on public Gradle API.

1. A `[module:task] Started` line when a task begins (from the task's own `doFirst`, since
   `BuildEventsListenerRegistry` only exposes finish events) and a `[module:task] outcome duration`
   line when it finishes, via `BuildEventsListenerRegistry.onTaskCompletion` — the same public hook
   `test-scheduling` uses to release worker-slot permits.
2. A full replacement for `com.adarshr.test-logger`'s per-test rendering, driven by
   `Test.addTestListener`/`addTestOutputListener`. Every line — including a failing test's stack
   trace — carries the module, JUnit namespace, class name and parameters; passing tests are a
   single quiet line; durations are always shown and colored past `slowThreshold`; and a heartbeat
   announces tests that are still running past a configurable threshold, since JUnit has nothing to
   report until they finish.

Both mechanisms print through one shared build service so a failing test's multi-line block is
atomic under `--parallel` — two modules' output can never interleave mid-block.

An earlier design also tried to prefix every raw line of Gradle's own console output, so a javac
warning would carry the same `[module:task]` prefix. That required reflectively replacing
`OutputEventRenderer`'s internal listener chain, which reliably deadlocked the Gradle client/daemon
shutdown handshake in testing, reproduced under both `--no-daemon` and a persistent daemon, with
daemons left permanently stuck. That approach was dropped; this plugin never touches Gradle-internal
classes, so raw build-log lines (compiler warnings, `> Task` headers) are unaffected.

## Usage

```groovy
// settings.gradle
plugins {
    id 'io.kestra.gradle.logger' version '<version>'
}

kestraLogger {
    enabled = true
    failOnConflictingTestLogger = true   // fail the build if com.adarshr.test-logger is also applied

    task {
        enabled = true
        colors = true
        skipOutcomes = ['NO-SOURCE']
    }

    test {
        slowThreshold = 2000
        showExceptions = true
        showFullStackTraces = true
        showCauses = true
        showPassedStandardStreams = false
        showPassedStandardError = false   // true to still print stderr for passing tests even with the above false
        showSkippedStandardStreams = true
        showFailedStandardStreams = true
        showStart = false       // true when GitHub Actions step debug logging is on (RUNNER_DEBUG=1)
        fullNamespace = false   // true to print "io.kestra.core.runners" instead of "i.k.c.runners"

        heartbeat {
            enabled = true
            threshold = 5000     // ms before a running test's first heartbeat line
            interval = 5000      // ms between subsequent heartbeat lines
            displayOutput = true // false to omit the buffered stdout/stderr lines from heartbeat output
        }

        heap {
            enabled = true          // sample actual peak JVM heap per module from worker-JVM GC logs
            interval = 120_000      // ms between "current heap" lines for a still-running test module; 0 to disable
        }
    }

    github {
        annotations = true   // ::error / ::warning — only when GITHUB_ACTIONS=true
        jobSummary = true    // $GITHUB_STEP_SUMMARY  — only when GITHUB_ACTIONS=true
    }
}
```

## Configuration

| Block | Property | Default | Meaning |
|---|---|---|---|
| `task` | `enabled` | `true` | Print a line for every finished task |
| `task` | `colors` | `true` | Auto-disabled when `NO_COLOR` is set |
| `task` | `skipOutcomes` | `[NO-SOURCE]` | Outcomes not worth a line |
| `test` | `slowThreshold` | `2000` | ms at/above which a duration renders red; half renders yellow |
| `test` | `showPassedStandardStreams` | `false` | Passing tests are always one line regardless |
| `test` | `showPassedStandardError` | `false` | `true` prints a passing test's buffered stderr even though stdout stays hidden; redundant when `showPassedStandardStreams = true` |
| `test` | `showStart` | `false`, or `true` when `RUNNER_DEBUG=1` | Print a line the moment each test starts, not just when it finishes |
| `test` | `fullNamespace` | `false` | `true` prints the package in full instead of collapsing it |
| `test.heartbeat` | `threshold` / `interval` | `5000` / `5000` | First heartbeat line, then cadence, for a still-running test |
| `test.heap` | `enabled` | `true` | Actual peak heap pressure per module (`used / capacity (percent%)`), sampled from worker-JVM `-Xlog:gc` output (`n/a` if no GC occurred) |
| `test.heap` | `interval` | `120_000` | ms between "current heap" lines for each still-running Test task; `0` disables them |
| `github` | `annotations` / `jobSummary` | `true` / `true` | Only emitted when `GITHUB_ACTIONS=true` |

## Edge cases

| Scenario | Behaviour |
|---|---|
| `com.adarshr.test-logger` also applied | Build fails with a message pointing at the equivalent `kestraLogger.test { }` settings, unless `failOnConflictingTestLogger = false` |
| `enabled = false` | Both mechanisms are fully silent; Test tasks are left with Gradle's own default logging |
| Configuration cache hit | Unlike `test-scheduling`, unaffected — `BuildEventsListenerRegistry` and the `Test.addTestListener`/`addTestOutputListener` registrations are both configuration-cache compatible, so task lines, test lines and the build-end summary all render normally |
| Non-GitHub-Actions CI / local dev | `github.annotations` / `github.jobSummary` are no-ops; everything else behaves the same |
| No GC occurred during a task (light test suite) | Rendered as `peak heap n/a`, never `0%`, so it can't be misread as "used no memory" |
