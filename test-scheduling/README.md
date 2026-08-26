# test-scheduling

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

## Requirements

- `./gradlew test --parallel --max-workers=N` (N > 1).
- Applied from `settings.gradle`, not `build.gradle`.
- `org.gradle.parallel=true` in `gradle.properties`.

## Usage

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

## Edge cases

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

## Configuration cache note

On a configuration cache hit, Gradle skips the configuration phase, so the service is never
armed. The build runs unthrottled — a safe degradation. Enable the config cache only if your
build is already compatible with it.
