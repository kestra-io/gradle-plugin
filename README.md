# gradle-plugins

Gradle plugins that help build Kestra plugins.

| Plugin | Purpose |
|---|---|
| [`io.kestra.gradle.spotless-conventions`](spotless-conventions/README.md) | Applies Spotless with Kestra formatting and git hooks. |
| [`io.kestra.gradle.develocity-conventions`](develocity-conventions/README.md) | Configures Develocity build scans and remote build cache. |
| [`io.kestra.gradle.repository-conventions`](repository-conventions/README.md) | Routes dependency resolution through Kestra's Maven proxy to avoid Maven Central rate limiting. |
| [`io.kestra.gradle.inject-bom-versions`](inject-bom-versions/README.md) | Injects missing versions for BOM-managed dependencies in the POM. |
| [`io.kestra.gradle.plugin-doc-lint`](plugin-doc-lint/README.md) | Enforces plugin documentation completeness at build time. |
| [`io.kestra.gradle.test-scheduling`](test-scheduling/README.md) | Reserves worker slots for heavy test modules so they run first and light modules expand to all slots once the heavy ones finish. |
| [`io.kestra.gradle.logger`](logger/README.md) | Opinionated build and test output for parallel multi-module CI builds: every task and test line prefixed by module, quiet passing tests, always-visible colored durations, and a heartbeat for tests that run long. |

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
