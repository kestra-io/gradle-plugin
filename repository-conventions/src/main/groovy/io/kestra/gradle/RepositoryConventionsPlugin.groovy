package io.kestra.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.OrganizationalContact
import org.cyclonedx.model.OrganizationalEntity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

class RepositoryConventionsPlugin implements Plugin<Project> {

    private static final String GCP_MAVEN_REMOTE_URL = "https://europe-maven.pkg.dev/kestra-host/maven-remote"
    private static final String SBOM_CLASSIFIER = "cyclonedx"
    private static final String SBOM_CONFIGURED = "kestraSbomConfigured"

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
        configureProjectSbom(project)
    }

    private static void configureProjectSbom(Project project) {
        // A subproject can be configured from the root and apply this plugin itself
        if (project.extensions.extraProperties.has(SBOM_CONFIGURED)) {
            return
        }
        project.extensions.extraProperties.set(SBOM_CONFIGURED, true)

        project.plugins.withId("java") {
            project.pluginManager.apply("org.cyclonedx.bom")

            def sbom = project.tasks.named("cyclonedxDirectBom", CyclonedxDirectTask) { task ->
                task.includeConfigs.set(["runtimeClasspath"])
                task.jsonOutput.set(project.layout.buildDirectory.file("sbom/java.json"))
                task.xmlOutput.convention((RegularFile) null)
                task.includeBomSerialNumber.set(false)
                task.organizationalEntity.set(kestraEntity())
                task.buildSystemEnvironmentVariable.set('${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}')
                task.mustRunAfter("classes")
                def output = task.jsonOutput
                task.doLast {
                    dropPomComponents(output.get().asFile)
                }
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

            // Named after the jar, whose name follows archiveBaseName and archiveVersion rather than the project
            def releaseAsset = project.tasks.register("copyCyclonedxSbom") { task ->
                def jar = project.tasks.named("jar", Jar)
                def source = sbom.flatMap { it.jsonOutput }
                def target = jar.map {
                    def version = it.archiveVersion.orNull
                    new File(it.destinationDirectory.get().asFile, "${it.archiveBaseName.get()}${version ? "-${version}" : ""}-${SBOM_CLASSIFIER}.json")
                }
                task.inputs.file(source)
                task.outputs.file(target)
                task.doLast {
                    target.get().parentFile.mkdirs()
                    target.get().bytes = source.get().asFile.bytes
                }
            }
            project.tasks.named("jar") { it.finalizedBy(releaseAsset) }
            project.plugins.withId("com.gradleup.shadow") {
                project.tasks.named("shadowJar") { it.finalizedBy(releaseAsset) }
            }

            project.plugins.withId("maven-publish") {
                project.extensions.getByType(PublishingExtension).publications.withType(MavenPublication).configureEach { pub ->
                    pub.artifact(sbom.flatMap { it.jsonOutput }) {
                        classifier = SBOM_CLASSIFIER
                        extension = "json"
                    }
                }
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
        project.subprojects { sub ->
            configureProjectSbom(sub)
        }

        project.tasks.named("build") {
            it.dependsOn("cyclonedxBom")
        }
    }

    private static OrganizationalEntity kestraEntity() {
        def contact = new OrganizationalContact()
        contact.name = "Security Team"
        contact.email = "security@kestra.io"

        def entity = new OrganizationalEntity()
        entity.name = "Kestra Technology"
        entity.urls = ["https://kestra.io"]
        entity.addContact(contact)
        entity
    }

    /**
     * Removes the {@code type=pom} components of a CycloneDX JSON file: they are the BOMs imported through a
     * platform, which constrain versions but are not bundled anywhere. A kept component that depended on a removed
     * one is linked to the removed component's own dependencies instead.
     */
    static void dropPomComponents(File file) {
        def bom = new JsonSlurper().parse(file, "UTF-8")
        def dropped = (bom.components ?: []).findAll { it.purl?.contains("type=pom") }*.get("bom-ref") as Set
        if (dropped.isEmpty()) {
            return
        }

        def children = (bom.dependencies ?: []).collectEntries { [(it.ref): it.dependsOn ?: []] }
        Closure resolve
        resolve = { String ref, Set seen ->
            if (!dropped.contains(ref)) {
                return [ref]
            }
            if (!seen.add(ref)) {
                return []
            }
            children.get(ref, []).collectMany { resolve(it, seen) }
        }

        bom.components = bom.components.findAll { !dropped.contains(it."bom-ref") }
        bom.dependencies = (bom.dependencies ?: []).findAll { !dropped.contains(it.ref) }.collect { dependency ->
            dependency.dependsOn = (dependency.dependsOn ?: []).collectMany { resolve(it, [] as Set) }.unique()
            dependency
        }
        file.setText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)), "UTF-8")
    }
}
