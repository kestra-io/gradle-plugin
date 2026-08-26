# spotless-conventions

Applies `com.diffplug.spotless` with Kestra's Java formatting rules and installs git hooks that
run it automatically.

**Behaviour**:

- Applies `com.diffplug.spotless` and configures its `java` block with Kestra's bundled import
  order (`eclipse-kestra.importorder`) and Eclipse formatter profile
  (`eclipse-java-kestra-style.xml`), extracted from the plugin's jar into
  `build/spotless-config/` by an `extractSpotlessConfig` task that every `spotless*` task depends
  on. `enforceCheck` is `false`, so `check` does not fail on formatting — only `spotlessApply`
  fixes it. Also enables `toggleOffOn()` and `removeUnusedImports()`.
- Targets `src/**/*.java` by default; pass `-PtargetFile=<path>` to format a single file instead.
- On the root project only, registers a `setupHooks` task that installs a `pre-commit` hook into
  `.github/.hooks/` from the plugin's bundled resources, wired into `build` via
  `build.dependsOn('setupHooks')`. Skipped (task disabled) if the root project has no `.git`
  directory, so it's a no-op in non-git contexts (e.g. a packaged source tree).

## Usage

```groovy
// build.gradle
plugins {
    id 'io.kestra.gradle.spotless-conventions' version '<version>'
}
```

```
./gradlew spotlessApply                    # format src/**/*.java
./gradlew spotlessApply -PtargetFile=path/to/File.java
```

No configuration surface — formatting rules are fixed to Kestra's style.
