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
