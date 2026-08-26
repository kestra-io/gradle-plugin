# inject-bom-versions

Injects missing versions for BOM-managed dependencies into the published POM.

**Problem**: when a dependency's version comes from a BOM or platform constraint rather than an
explicit `version` in `build.gradle`, Gradle publishes the POM's `<dependency>` (and
`<dependencyManagement><dependency>`) entries without a `<version>` element. Consumers that don't
also import the same BOM then fail to resolve those dependencies.

**Behaviour**: hooks `MavenPublication.pom.withXml` for every `maven-publish` publication. For any
`<dependency>` node — in `<dependencies>` or `<dependencyManagement><dependencies>` — missing a
`<version>`, it looks up the resolved version from `compileClasspath`'s first-level module
dependencies and appends it. Dependencies it can't resolve a version for are logged as a warning,
not failed.

## Usage

```groovy
// build.gradle
plugins {
    id 'io.kestra.gradle.inject-bom-versions' version '<version>'
    id 'maven-publish'
}
```

Runs automatically on publish, in `afterEvaluate`. No configuration surface.
