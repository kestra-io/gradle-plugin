# gradle-plugins

Gradle plugins that help build Kestra plugins.

- `io.kestra.gradle.spotless-conventions` - applies Spotless with Kestra formatting and git hooks.
- `io.kestra.gradle.develocity-conventions` - configures Develocity build scans and remote build cache.
- `io.kestra.gradle.inject-bom-versions` - injects missing versions for BOM-managed dependencies in the POM.
- `io.kestra.gradle.plugin-doc-lint` - enforces plugin documentation completeness at build time.
- `io.kestra.gradle.test-scheduling` - reserves worker slots for heavy test modules so they run first and light modules expand to all slots once the heavy ones finish.
- `io.kestra.gradle.logger` - opinionated build and test output for parallel multi-module CI builds: every task and test line prefixed by module, quiet passing tests, always-visible colored durations, and a heartbeat for tests that run long.

## plugin-doc-lint

Checks that a Kestra plugin ships complete documentation: subpackage metadata, icons,
`@Schema` annotations, `@Plugin` examples, property groups, secret annotations, and
`package-info`. It catches gaps at build time instead of code review.

Covers tasks, triggers, task runners, log exporters and output classes. Multi-module
plugins are supported: a submodule may ship a leaf-named metadata file instead of
`index.yaml`.

Apply it:

```groovy
plugins {
    id 'io.kestra.gradle.plugin-doc-lint'
}
```

It registers a `lintPluginDocs` task and wires it into `check`, so `./gradlew check` fails on
any violation. Annotation checks reflect over the compiled classes, so the task depends on
`classes`. Each violation reports a rule id, the class or file path, and a one-line fix.

### Configuration

```groovy
pluginDocLint {
    // skip specific rules by id
    disabledRules = ['DOC-001', 'DOC-003']
    // report violations without failing the build
    ignoreFailures = false
}
```

### Rules

Metadata (`src/main/resources/metadata/`):

| Rule | Check |
|---|---|
| META-001 | `index.yaml` exists for the root package |
| META-002 | every package with a task or trigger has a `<subpackage>.yaml` |
| META-003 | `group`, `name`, `title`, `description` are non-empty and a `body` field is present |
| META-004 | `group` matches the fully-qualified package name |

Icons (`src/main/resources/icons/`):

| Rule | Check |
|---|---|
| ICON-001 | `plugin-icon.svg` exists |
| ICON-002 | each subpackage has `<fully-qualified-subpackage>.svg` |

`@Schema`:

| Rule | Check |
|---|---|
| SCHEMA-001 | every task and trigger class has `@Schema` |
| SCHEMA-002 | the class-level `@Schema` on a task or trigger has a non-empty `description` |
| SCHEMA-003 | every non-static, non-transient field in a task, trigger, task runner or log exporter has `@Schema` |
| SCHEMA-004 | every field in an output class has `@Schema` |
| SCHEMA-005 | no `@Schema` `title` ends with a period (titles inherited from a framework type the plugin cannot edit are exempt) |

`@Plugin` / `@Example`:

| Rule | Check |
|---|---|
| PLUGIN-001 | every task and trigger has `@Plugin(examples = ...)` with at least one example |
| PLUGIN-002 | each `@Example` has `full = true` |
| PLUGIN-003 | each `@Example` is valid YAML with `id:`, `namespace:`, and `tasks:` or `triggers:` |
| PLUGIN-004 | no `@Example` sets `lang = "yaml"` (it is the default) |
| PLUGIN-005 | no example holds a plain-text secret, use `{{ secret('NAME') }}` |

Properties:

| Rule | Check |
|---|---|
| PROP-001 | every `@PluginProperty` group is one of `main`, `connection`, `source`, `processing`, `execution`, `destination`, `reliability`, `advanced`, `deprecated` |
| PROP-002 | fields matching known secret-name patterns (`password`, `apiKey`, `privateKey`, `secret`, `credential`, `apiToken`, `accessToken`, `refreshToken`, ...) use `@PluginProperty(secret = true)`. Matched by concrete credential forms so pagination cursors like `pageToken` are not flagged |
| PROP-003 | no property is named `version` |

Package structure:

| Rule | Check |
|---|---|
| PKG-001 | every package with a task or trigger has a `package-info.java` with `@PluginSubGroup` |
| PKG-002 | the `@PluginSubGroup` category is one of `AI`, `BUSINESS`, `CLOUD`, `CORE`, `DATA`, `INFRASTRUCTURE` |
| PKG-003 | tasks and triggers are not split between the root package and subpackages |

Documentation (`src/main/resources/doc/`):

| Rule | Check |
|---|---|
| DOC-001 | `doc/<root-package>.md` exists |
| DOC-002 | markdown files referenced from `metadata/*.yaml` exist in `doc/` |
| DOC-003 | how-to markdown files have at least 10 lines |

PLUGIN-004 is checked against the source text, because reflection cannot tell an explicit
`lang = "yaml"` from the default. META-003 requires `body` to be present but allows it to be
empty, matching how current plugins ship it.

## test-scheduling

Reserves worker slots for high-priority test modules so they are not starved by lighter modules
that become dependency-ready first.

**Problem**: Gradle's scheduler has no duration awareness. When a multi-module build runs with
`--parallel`, small modules that compile quickly can grab every worker slot, forcing the large
modules to start late and set the critical path.

**Behaviour**: two mechanisms.

1. *Plan ordering* — the priority projects' copies of the requested lifecycle tasks are moved to the
   front of the requested task list. Gradle builds the execution plan by walking the requested tasks
   in order, inserting each one's dependencies before it, and then picks ready nodes in that order —
   so the priority modules' whole compile chain (their own `compileJava`/`jar` plus those of every
   upstream project) is scheduled first. Without this, reserving slots achieves nothing: a priority
   test is not dependency-ready until its chain is built, and while that chain queues behind dozens of
   light-module compilations the reserved slots sit idle.
2. *Slot reservation* — priority projects run unthrottled; non-priority Test tasks share
   `maxWorkers - reservedSlots` concurrent slots while any priority task is still running. Once all
   priority tasks finish the reserved slots are released and non-priority tasks expand to the full
   worker count.

Ordering rules are deliberately not used: `shouldRunAfter` is ignored by the parallel scheduler, and
any ordering rule added from `taskGraph.whenReady` is ignored outright because the execution plan is
already built by the time that listener fires.

### Requirements

- `./gradlew test --parallel --max-workers=N` (N > 1).
- Applied from `settings.gradle`, not `build.gradle`.
- `org.gradle.parallel=true` in `gradle.properties`.

### Usage

```groovy
// settings.gradle
plugins {
    id 'io.kestra.gradle.test-scheduling' version '<version>'
}

kestraTestScheduling {
    priority = [':core', ':tests']   // project paths whose Test tasks run unthrottled
    // reservedSlots = 2             // default: auto (min(priority task count, maxWorkers - 1))
    // enabled = true
    // preferPriorityFirst = true    // move the priority chain to the head of the execution plan
}
```

### Edge cases

| Scenario | Behaviour |
|---|---|
| `--max-workers=1` | Service not armed; build runs normally |
| `-x :core:test` (priority task excluded) | Missing priority tasks ignored; reserved recalculated |
| No non-priority Test tasks in the graph | Service not armed |
| Priority task fails with `--continue` | Slot released via `BuildEventsListenerRegistry`, not `doLast` |
| Configuration cache hit | Service is never armed (config skipped); light tasks run unthrottled |
| `./gradlew test --tests Foo` | Plan reordering skipped: task options bind to the task name they follow, so prepending a task would run the priority module unfiltered |
| `./gradlew :light1:test` | Project-qualified names are left alone; only plain lifecycle names are reordered |
| Priority project has no such task | That task name is skipped, the rest of the invocation is unchanged |

### Configuration cache note

On a configuration cache hit, Gradle skips the configuration phase, so the service is never
armed. The build runs unthrottled — a safe degradation. Enable the config cache only if your
build is already compatible with it.

## logger

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

### Usage

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
            interval = 120_000      // ms between "current heap" lines for a still-running test task; 0 to disable
        }
    }

    github {
        annotations = true   // ::error / ::warning — only when GITHUB_ACTIONS=true
        jobSummary = true    // $GITHUB_STEP_SUMMARY  — only when GITHUB_ACTIONS=true
    }
}
```

### Configuration

| Block | Property | Default | Meaning |
|---|---|---|---|
| `task` | `enabled` | `true` | Print a line for every finished task |
| `task` | `colors` | `true` | Auto-disabled when `NO_COLOR` is set |
| `task` | `skipOutcomes` | `[NO-SOURCE]` | Outcomes not worth a line |
| `test` | `slowThreshold` | `2000` | ms at/above which a duration renders red; half renders yellow |
| `test` | `showPassedStandardStreams` | `false` | Passing tests are always one line regardless |
| `test` | `showStart` | `false`, or `true` when `RUNNER_DEBUG=1` | Print a line the moment each test starts, not just when it finishes |
| `test` | `fullNamespace` | `false` | `true` prints the package in full instead of collapsing it |
| `test.heartbeat` | `threshold` / `interval` | `5000` / `5000` | First heartbeat line, then cadence, for a still-running test |
| `test.heap` | `enabled` | `true` | Actual peak heap pressure per module (`used / capacity (percent%)`), sampled from worker-JVM `-Xlog:gc` output (`n/a` if no GC occurred) |
| `test.heap` | `interval` | `120_000` | ms between "current heap" lines for each still-running Test task; `0` disables them |
| `github` | `annotations` / `jobSummary` | `true` / `true` | Only emitted when `GITHUB_ACTIONS=true` |

### Edge cases

| Scenario | Behaviour |
|---|---|
| `com.adarshr.test-logger` also applied | Build fails with a message pointing at the equivalent `kestraLogger.test { }` settings, unless `failOnConflictingTestLogger = false` |
| `enabled = false` | Both mechanisms are fully silent; Test tasks are left with Gradle's own default logging |
| Configuration cache hit | Unlike `test-scheduling`, unaffected — `BuildEventsListenerRegistry` and the `Test.addTestListener`/`addTestOutputListener` registrations are both configuration-cache compatible, so task lines, test lines and the build-end summary all render normally |
| Non-GitHub-Actions CI / local dev | `github.annotations` / `github.jobSummary` are no-ops; everything else behaves the same |
| No GC occurred during a task (light test suite) | Rendered as `peak heap n/a`, never `0%`, so it can't be misread as "used no memory" |

## Releasing

Releases are tag-based off `main`, using `net.researchgate.release`. To cut a release:

```
git checkout main && git pull
./gradlew release
```

It bumps the version, tags `v<version>`, pushes, and bumps `main` to the next `-SNAPSHOT`. The
pushed tag triggers CI, which publishes all modules to Maven Central and creates the GitHub
release. One release versions all plugins together.

A new patch, minor or major is just the next version number, no release branch needed.
