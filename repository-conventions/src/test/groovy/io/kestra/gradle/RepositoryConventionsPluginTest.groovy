package io.kestra.gradle

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

    @Test
    void 'tests write allure results and the adapter version is pinned'() {
        testProjectDir.resolve('src/test/java').toFile().mkdirs()
        testProjectDir.resolve('src/test/java/SampleTest.java').toFile().text = """
            import org.junit.jupiter.api.Test;
            class SampleTest {
                @Test void ok() {}
            }
        """
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.repository-conventions'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }

            test {
                useJUnitPlatform()
            }

            tasks.register('allureJavaVersion') {
                def version = allure.adapter.allureJavaVersion
                def outputs = tasks.named('test').get().outputs.files
                doLast {
                    println "ALLURE_JAVA_VERSION=" + version.get()
                    println "TEST_OUTPUTS=" + outputs.files*.path
                }
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('test', 'allureJavaVersion')
                .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':test').outcome)
        assertTrue(result.output.contains('ALLURE_JAVA_VERSION=3.1.1'), result.output)

        assertTrue(result.output.contains('build/allure-results'), 'allure-results is not a test task output: ' + result.output)

        def results = testProjectDir.resolve('build/allure-results').toFile()
        assertTrue(results.listFiles().any { it.name.endsWith('-result.json') }, "allure-results: ${results.list()}")
    }
}
