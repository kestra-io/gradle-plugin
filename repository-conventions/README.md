# repository-conventions

Routes dependency resolution through Kestra's GCP Artifact Registry Maven proxy, ahead of Maven
Central, and embeds a CycloneDX SBOM of the runtime classpath in the jar.

**Problem**: parallel multi-module CI builds hitting Maven Central directly can trip its rate
limiting (HTTP 429).

**Behaviour**: if the `MAVEN_REMOTE_TOKEN` environment variable is set, adds
`https://europe-maven.pkg.dev/kestra-host/maven-remote` as a Maven repository authenticated via
OAuth2 access token (`oauth2accesstoken` / `$MAVEN_REMOTE_TOKEN`), and moves it to the front of the
repository list in `afterEvaluate` — `repositories.maven()` normally appends, and this proxy needs
to be checked first to avoid rate-limit errors from Maven Central. If `MAVEN_REMOTE_TOKEN` is
unset (e.g. local dev), the plugin is a no-op and repositories are left as declared.

## Usage

```groovy
// build.gradle
plugins {
    id 'io.kestra.gradle.repository-conventions' version '<version>'
}
```

No configuration surface; the proxy is driven by `MAVEN_REMOTE_TOKEN`, the SBOM is always on.

## SBOM

On any project with the `java` plugin, applies the [CycloneDX Gradle plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin)
and generates a CycloneDX JSON SBOM of the `runtimeClasspath` only (no test or compile-only
dependencies) at `build/sbom/java.json`. It runs after compilation and is embedded in the `jar`
(and `shadowJar` when `com.gradleup.shadow` is applied) as `META-INF/sbom/java.json`, so every
`build` / `assemble` produces it. Does not depend on `MAVEN_REMOTE_TOKEN`.

Run it alone with `./gradlew cyclonedxDirectBom`.

The SBOM lists what the jar bundles and nothing else:

- The `type=pom` components, the BOMs imported through a platform such as `io.kestra:platform`, are removed.
  They only constrain versions. A component that depended on one is linked to that BOM's own dependencies.
- No `serialNumber` is written. The other volatile field, `metadata.timestamp`, stays, so the jar is still
  not reproducible: attest the jar you publish, do not rebuild it in between.
- The `manufacturer` is Kestra Technology, and the build system URL comes from the GitHub Actions environment.

On any project with the `maven-publish` plugin, the SBOM is added to every `MavenPublication` as a
`<artifactId>-<version>-cyclonedx.json` artifact (classifier `cyclonedx`), so it is published, and signed, with the jar.
The `jar` (and `shadowJar`) task also leaves a copy at `build/libs/<name>-<version>-cyclonedx.json`, which is where a
release step that uploads `build/libs` picks it up. A build script that already adds a `cyclonedx` artifact to a
publication must drop it when it upgrades to this version, since Gradle rejects two artifacts with the same
classifier and extension.

When applied on the root project, every subproject gets the same treatment (SBOM, embedding, publication
artifact), whether or not it applies this plugin itself.

When applied on the root project (with or without the `java` plugin), `build` also runs
`cyclonedxBom`, which merges the root and every subproject into a single deduplicated SBOM at
`build/reports/cyclonedx/bom.json`. The `base` plugin is applied on the root to provide the
`build` task, and every subproject's SBOM stays scoped to `runtimeClasspath`, even in subprojects
that don't apply this plugin.
