package io.kestra.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

class KestraSpotlessConventionsPluginTest {
    @TempDir
    Path testProjectDir
    File buildFile
    File settingsFile

    @BeforeEach
    void setup() {
        buildFile = testProjectDir.resolve('build.gradle').toFile()
        settingsFile = testProjectDir.resolve('settings.gradle').toFile()
        settingsFile.text = "rootProject.name = 'test-project'"
    }

    @Test
    void 'plugin applies successfully'() {
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.spotless-conventions'
            }
            
            repositories {
                mavenCentral()
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('tasks')
                .build()
        assertTrue(result.output.contains('setupHooks'))
        assertEquals(TaskOutcome.SUCCESS, result.task(':tasks').outcome)
    }

    @Test
    void 'setupHooks task is registered'() {
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.spotless-conventions'
            }
            
            repositories {
                mavenCentral()
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('tasks', '--all')
                .build()
        assertTrue(result.output.contains('setupHooks'))
    }

    @Test
    void 'plugin configures spotless extension'() {
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.spotless-conventions'
            }
            repositories {
                mavenCentral()
            }
            tasks.register('checkSpotlessConfig') {
                doLast {
                    def spotless = project.extensions.findByName('spotless')
                    
                    println "Spotless configured: " + (spotless != null)
                    println "Enforce check: " + spotless.enforceCheck
                }
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('checkSpotlessConfig')
                .build()
        assertTrue(result.output.contains('Spotless configured: true'))
        assertTrue(result.output.contains('Enforce check: false'))
    }

    @Test
    void 'plugin applies spotless with java formatting'() {
        // Create a properly structured project
        def srcDir = testProjectDir.resolve('src/main/java')
        Files.createDirectories(srcDir)
        def javaFile = srcDir.resolve('Example.java')
        javaFile.text = '''public class Example {
    public void method(  )   {
        System.out.println(  "Hello"  );
    }
}'''
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.spotless-conventions'
            }
            
            repositories {
                mavenCentral()
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('spotlessCheck')
                .buildAndFail()
        // Will fail because code doesn't match format, but task should run
        assertTrue(result.output.contains('spotless'))
    }

    @Test
    void 'plugin skip setupHooks when git not present'() {
        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.spotless-conventions'
            }
            
            repositories {
                mavenCentral()
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('setupHooks')
                .build()
        assertTrue(result.output.contains('[KestraSpotless] No .git directory found; skipping hook activation to avoid executing git-related commands in non-git projects'))
    }

    @Test
    void 'setupHooks is dependency of build task'() {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.spotless-conventions'
            }
            
            repositories {
                mavenCentral()
            }
        """
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('build', '--dry-run')
                .build()
        assertTrue(result.output.contains('setupHooks'))
    }

    @Test
    void 'spotless java target uses custom targetFile when provided'() {
        def srcDir = testProjectDir.resolve('src/main/java')
        Files.createDirectories(srcDir)
        def javaFile = srcDir.resolve('TestClass.java')
        javaFile.text = """
            public class TestClass {
                public static void main(String[] args) {
                }
            }
        """

        buildFile.text = """
            plugins {
                id 'io.kestra.gradle.spotless-conventions'
            }

            repositories {
                mavenCentral()
            }
        """

        // First apply spotless to auto-fix formatting issues, then verify the check passes
        def applyResult = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('spotlessApply', '-PtargetFile=src/main/java/TestClass.java')
                .build()

        assertTrue(applyResult.output.contains('spotlessApply'))

        def checkResult = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('spotlessJavaCheck', '-PtargetFile=src/main/java/TestClass.java')
                .build()

        assertEquals(TaskOutcome.SUCCESS, checkResult.task(':spotlessJavaCheck').outcome)
    }
}
