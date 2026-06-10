package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Generates plugin doc/schema at build time and bundles it into the jar under
 * META-INF/kestra/docs/, so the API can read it from the jar instead of regenerating at runtime.
 *
 * It runs the existing kestra-core DocumentationGenerator with the plugin's own classpath (which
 * has core and core-ee), so EE plugins generate without the classloader isolation that breaks the
 * OSS CLI. A small launcher (shipped as a resource) is written into the build, compiled against the
 * plugin's classpath so it matches the plugin's kestra version, then executed.
 */
class KestraPluginDocsConventionsPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.plugins.withId('java') {
            def buildDir = project.layout.buildDirectory
            def srcDir = buildDir.dir('docgen/src')
            def classesOutDir = buildDir.dir('docgen/classes')
            // Written into the main resources dir so the jar task packages it automatically.
            def docsOutDir = buildDir.dir('resources/main/META-INF/kestra/docs')

            ClassLoader pluginClassLoader = this.getClass().getClassLoader()

            // The plugin declares kestra-core as compileOnly, so core's runtime-only transitives
            // (the JSON-schema lib, etc.) aren't on its classpath. Resolve core with full transitives
            // into a dedicated configuration for the doc-gen run.
            def kestraVersion = project.findProperty('kestraVersion')
            def docgenRuntime = project.configurations.create('kestraDocgenRuntime') { it.canBeConsumed = false }
            if (kestraVersion != null) {
                project.dependencies.add('kestraDocgenRuntime', "io.kestra:core:${kestraVersion}")
            }

            def writeLauncher = project.tasks.register('writeDocGenLauncher') { task ->
                task.outputs.dir(srcDir)
                task.doLast {
                    def target = new File(srcDir.get().asFile, 'io/kestra/gradle/docgen/BuildTimeDocGen.java')
                    target.parentFile.mkdirs()
                    target.text = pluginClassLoader.getResource('docgen/BuildTimeDocGen.java.tmpl').getText('UTF-8')
                }
            }

            def compileLauncher = project.tasks.register('compileDocGenLauncher', JavaCompile) { task ->
                task.dependsOn(writeLauncher)
                task.source = project.fileTree(srcDir)
                task.classpath = project.sourceSets.main.compileClasspath
                task.destinationDirectory.set(classesOutDir)
            }

            def generateDocs = project.tasks.register('generatePluginDocs', JavaExec) { task ->
                task.group = 'documentation'
                task.description = 'Generate plugin doc/schema and bundle it into the jar'
                task.dependsOn('compileJava', 'processResources', compileLauncher)

                task.classpath = project.files(
                    classesOutDir,
                    project.sourceSets.main.output,
                    project.sourceSets.main.compileClasspath,
                    docgenRuntime
                )
                task.mainClass.set('io.kestra.gradle.docgen.BuildTimeDocGen')
                task.args = [
                    project.sourceSets.main.output.classesDirs.singleFile.absolutePath,
                    docsOutDir.get().asFile.absolutePath
                ]
            }

            project.tasks.withType(Jar).configureEach { it.dependsOn(generateDocs) }
        }
    }
}
