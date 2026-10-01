package io.kestra.gradle

import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.jvm.tasks.Jar

class RepositoryConventionsPlugin implements Plugin<Project> {

    private static final String GCP_MAVEN_REMOTE_URL = "https://europe-maven.pkg.dev/kestra-host/maven-remote"

    @Override
    void apply(Project project) {
        configureMavenRemote(project)
        configureSbom(project)
    }

    private static void configureMavenRemote(Project project) {
        def token = System.getenv("MAVEN_REMOTE_TOKEN")
        if (!token) {
            return
        }

        project.afterEvaluate {
            // ponytail: remove+addFirst because repositories.maven() appends; proxy must be first to avoid 429s on Maven Central
            def repo = project.repositories.maven {
                url = GCP_MAVEN_REMOTE_URL
                credentials {
                    username = "oauth2accesstoken"
                    password = token
                }
            }
            project.repositories.remove(repo)
            project.repositories.addFirst(repo)
        }
    }

    private static void configureSbom(Project project) {
        if (project == project.rootProject) {
            configureAggregateSbom(project)
        }

        project.plugins.withId("java") {
            project.pluginManager.apply("org.cyclonedx.bom")

            def sbom = project.tasks.named("cyclonedxDirectBom", CyclonedxDirectTask) { task ->
                task.includeConfigs.set(["runtimeClasspath"])
                task.jsonOutput.set(project.layout.buildDirectory.file("sbom/java.json"))
                task.xmlOutput.convention((RegularFile) null)
                task.mustRunAfter("classes")
            }

            def embed = { Jar jar ->
                jar.from(sbom.flatMap { it.jsonOutput }) {
                    into "META-INF/sbom"
                }
            }
            project.tasks.named("jar", Jar, embed)
            // Kestra plugins publish a shadow jar, which doesn't reuse the jar task's content
            project.plugins.withId("com.gradleup.shadow") {
                project.tasks.named("shadowJar", Jar, embed)
            }
        }
    }

    private static void configureAggregateSbom(Project project) {
        // base gives a root aggregator project a build task to hook the merged SBOM on
        project.pluginManager.apply("base")
        project.pluginManager.apply("org.cyclonedx.bom")

        // CycloneDX registers direct tasks on every subproject from the root, including ones that never apply this plugin
        project.allprojects { p ->
            p.tasks.withType(CyclonedxDirectTask).configureEach { task ->
                task.includeConfigs.set(["runtimeClasspath"])
            }
        }

        project.tasks.named("build") {
            it.dependsOn("cyclonedxBom")
        }
    }
}
