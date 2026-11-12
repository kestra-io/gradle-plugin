package io.kestra.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class RepositoryConventionsPluginTest {
    @TempDir
    Path testProjectDir
    File buildFile

    @BeforeEach
    void setup() {
        buildFile = testProjectDir.resolve('build.gradle').toFile()
        testProjectDir.resolve('settings.gradle').toFile().text = "rootProject.name = 'test-project'"
    }

    @Test
    void 'jar embeds runtime SBOM'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.repository-conventions'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.slf4j:slf4j-api:2.0.13'
                testImplementation 'org.junit.jupiter:junit-jupiter-api:5.9.3'
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jar')
                .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':cyclonedxDirectBom').outcome)

        def jar = testProjectDir.resolve('build/libs/test-project.jar').toFile()
        def sbom = new ZipFile(jar).withCloseable { zip ->
            def entry = zip.getEntry('META-INF/sbom/java.json')
            assertNotNull(entry, 'META-INF/sbom/java.json missing from jar')
            new JsonSlurper().parse(zip.getInputStream(entry))
        }

        assertEquals('CycloneDX', sbom.bomFormat)
        def names = sbom.components*.name
        assertTrue(names.contains('slf4j-api'), "components: $names")
        assertFalse(names.contains('junit-jupiter-api'), "components: $names")
    }

    @Test
    void 'build on root aggregator merges module SBOMs'() {
        testProjectDir.resolve('settings.gradle').toFile().text = """
            rootProject.name = 'test-project'
            include 'a', 'b'
        """
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.repository-conventions'
            }

            subprojects {
                apply plugin: 'java'
                apply plugin: 'io.kestra.gradle.repository-conventions'
                repositories {
                    mavenCentral()
                }
            }
        """
        testProjectDir.resolve('a').toFile().mkdirs()
        testProjectDir.resolve('a/build.gradle').toFile().text = """
            dependencies {
                implementation 'org.slf4j:slf4j-api:2.0.13'
            }
        """
        testProjectDir.resolve('b').toFile().mkdirs()
        testProjectDir.resolve('b/build.gradle').toFile().text = """
            dependencies {
                implementation project(':a')
                implementation 'org.slf4j:slf4j-api:2.0.13'
                testImplementation 'org.junit.jupiter:junit-jupiter-api:5.9.3'
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('build')
                .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':cyclonedxBom').outcome)

        def sbom = new JsonSlurper().parse(testProjectDir.resolve('build/reports/cyclonedx/bom.json').toFile())
        def names = sbom.components*.name
        assertEquals(1, names.count('slf4j-api'), "components: $names")
        assertTrue(names.containsAll(['a', 'b']), "components: $names")
        assertFalse(names.contains('junit-jupiter-api'), "components: $names")
    }

    @Test
    void 'direct SBOM drops platform BOM components and has no serial number'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.repository-conventions'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation platform('com.fasterxml.jackson:jackson-bom:2.17.0')
                implementation 'com.fasterxml.jackson.core:jackson-core'
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('cyclonedxDirectBom')
                .build()

        def sbom = new JsonSlurper().parse(testProjectDir.resolve('build/sbom/java.json').toFile())
        def names = sbom.components*.name
        assertTrue(names.contains('jackson-core'), "components: $names")
        assertFalse(names.contains('jackson-bom'), "components: $names")
        assertTrue(sbom.components.every { !it.purl.contains('type=pom') }, "components: $names")
        assertNull(sbom.serialNumber)

        def refs = sbom.components*.'bom-ref' + sbom.metadata.component.'bom-ref'
        sbom.dependencies.each { dependency ->
            assertTrue(refs.contains(dependency.ref), "unknown ref ${dependency.ref}")
            assertTrue(refs.containsAll(dependency.dependsOn ?: []), "dangling dependsOn of ${dependency.ref}")
        }
        assertEquals('Kestra Technology', sbom.metadata.manufacturer.name)
    }

    @Test
    void 'dropping a pom component links its parents to its children'() {
        def file = testProjectDir.resolve('bom.json').toFile()
        file.text = JsonOutput.toJson([
                components  : [
                        [name: 'bom', purl: 'pkg:maven/g/bom@1?type=pom', 'bom-ref': 'bom'],
                        [name: 'lib', purl: 'pkg:maven/g/lib@1?type=jar', 'bom-ref': 'lib'],
                ],
                dependencies: [
                        [ref: 'root', dependsOn: ['bom']],
                        [ref: 'bom', dependsOn: ['lib']],
                        [ref: 'lib'],
                ],
        ])

        RepositoryConventionsPlugin.dropPomComponents(file)

        def bom = new JsonSlurper().parse(file)
        assertEquals(['lib'], bom.components*.name)
        assertEquals(['root', 'lib'], bom.dependencies*.ref)
        assertEquals(['lib'], bom.dependencies.find { it.ref == 'root' }.dependsOn)
    }

    @Test
    void 'jar leaves the SBOM next to it as a release asset'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.repository-conventions'
            }

            version = '1.2.3'

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.slf4j:slf4j-api:2.0.13'
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jar')
                .build()

        def asset = testProjectDir.resolve('build/libs/test-project-1.2.3-cyclonedx.json').toFile()
        assertTrue(asset.exists())
        assertEquals(testProjectDir.resolve('build/sbom/java.json').toFile().text, asset.text)
    }

    @Test
    void 'shadow jar embeds the SBOM and leaves it as a release asset'() {
        buildFile.text = """
            plugins {
                id 'java-library'
                id 'com.gradleup.shadow' version '9.6.1'
                id 'io.kestra.gradle.repository-conventions'
            }

            version = '1.2.3'

            repositories {
                mavenCentral()
            }

            dependencies {
                api 'org.slf4j:slf4j-api:2.0.13'
            }

            shadowJar {
                archiveClassifier.set(null)
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('shadowJar')
                .build()

        def jar = testProjectDir.resolve('build/libs/test-project-1.2.3.jar').toFile()
        assertNotNull(new ZipFile(jar).withCloseable { it.getEntry('META-INF/sbom/java.json') })
        assertTrue(testProjectDir.resolve('build/libs/test-project-1.2.3-cyclonedx.json').toFile().exists())
    }

    @Test
    void 'release asset follows the archive name of the jar'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.repository-conventions'
            }

            version = '1.2.3'
            base.archivesName = 'custom-name'

            repositories {
                mavenCentral()
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jar')
                .build()

        assertTrue(testProjectDir.resolve('build/libs/custom-name-1.2.3.jar').toFile().exists())
        assertTrue(testProjectDir.resolve('build/libs/custom-name-1.2.3-cyclonedx.json').toFile().exists())
    }

    @Test
    void 'maven publication carries the SBOM with the cyclonedx classifier'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'maven-publish'
                id 'io.kestra.gradle.repository-conventions'
            }

            group = 'io.test'
            version = '1.2.3'

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.slf4j:slf4j-api:2.0.13'
            }

            publishing {
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
                repositories {
                    maven {
                        url = layout.buildDirectory.dir('repo')
                    }
                }
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('publish')
                .build()

        def published = testProjectDir.resolve('build/repo/io/test/test-project/1.2.3/test-project-1.2.3-cyclonedx.json').toFile()
        assertTrue(published.exists())
        assertEquals('CycloneDX', new JsonSlurper().parse(published).bomFormat)
    }

    @Test
    void 'build on root aggregator embeds and publishes the SBOM of each module'() {
        testProjectDir.resolve('settings.gradle').toFile().text = """
            rootProject.name = 'test-project'
            include 'a'
        """
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.repository-conventions'
            }

            subprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                group = 'io.test'
                version = '1.2.3'
                repositories {
                    mavenCentral()
                }
                publishing {
                    publications {
                        maven(MavenPublication) {
                            from components.java
                        }
                    }
                    repositories {
                        maven {
                            url = rootProject.layout.buildDirectory.dir('repo')
                        }
                    }
                }
            }
        """
        testProjectDir.resolve('a').toFile().mkdirs()
        testProjectDir.resolve('a/build.gradle').toFile().text = """
            dependencies {
                implementation 'org.slf4j:slf4j-api:2.0.13'
            }
        """
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('build', 'publish')
                .build()

        def jar = testProjectDir.resolve('a/build/libs/a-1.2.3.jar').toFile()
        assertNotNull(new ZipFile(jar).withCloseable { it.getEntry('META-INF/sbom/java.json') })
        assertTrue(testProjectDir.resolve('build/repo/io/test/a/1.2.3/a-1.2.3-cyclonedx.json').toFile().exists())
    }

    @Test
    void 'no direct SBOM task on non java subproject'() {
        testProjectDir.resolve('settings.gradle').toFile().text = """
            rootProject.name = 'test-project'
            include 'a'
        """
        testProjectDir.resolve('a').toFile().mkdirs()
        testProjectDir.resolve('a/build.gradle').toFile().text = """
            plugins {
                id 'io.kestra.gradle.repository-conventions'
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(':a:tasks', '--all')
                .build()
        assertFalse(result.output.contains('cyclonedxDirectBom'))
    }
}
