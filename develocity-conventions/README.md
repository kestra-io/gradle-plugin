# develocity-conventions

Configures Develocity build scans and the remote build cache for Kestra builds.

**Behaviour**: a `Settings` plugin — apply it from `settings.gradle`. It applies
`com.gradle.develocity` and `com.gradle.common-custom-user-data-gradle-plugin`, then:

- Points build scans at `https://develocity.kestra.io`, uploads in the foreground on CI (so the
  build doesn't exit before the upload completes) and in the background locally, and only
  publishes scans for authenticated users.
- Enables the remote build cache, pushing to it only on CI (`CI` env var set) so local builds
  pull from the shared cache without polluting it.
- On CI, appends each published build scan's id, URI, timestamp and task names as a JSON line to
  `develocity-scan-output.ndjson` in the root directory, for later collection by CI tooling.
- Records every `Test` task's `maxHeapSize` as a build scan custom value (`<task path>#maxHeapSize`),
  across all projects.

## Usage

```groovy
// settings.gradle
plugins {
    id 'io.kestra.gradle.develocity-conventions' version '<version>'
}
```

No configuration surface — behaviour is fixed to Kestra's Develocity setup.
