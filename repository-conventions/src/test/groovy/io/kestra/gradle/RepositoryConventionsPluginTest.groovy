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
    void 'no SBOM task without java plugin'() {
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.repository-conventions'
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('tasks', '--all')
                .build()
        assertFalse(result.output.contains('cyclonedxDirectBom'))
    }
}
