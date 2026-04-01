package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that generates JSON schema & markdown files for Kestra plugin classes at build time.
 * <p>
 * The plugin:
 * <ul>
 *   <li>Registers a {@code generatepluginDocs} task of type {@link GeneratePluginDocTask}</li>
 *   <li>Hooks into {@code jar} and {@code shadowJar} tasks to include generated schemas under {@code docs/}</li>
 * </ul>
 * <p>
 * Apply this plugin and optionally configure it via the {@code pluginDoc} extension:
 * <pre>
 * pluginDoc {
 *     packagePrefix = 'io.kestra'
 * }
 * </pre>
 */
class PluginDocPlugin implements Plugin<Project> {
    void apply(Project project) {
        def extension = project.extensions.create('pluginDoc', PluginDocExtension)

        project.pluginManager.apply('java')

        def generatedSchemaDir = project.layout.buildDirectory.dir('generated-docs')

        // Create a detached configuration to resolve io.kestra:core for doc generation.
        // Runtime attributes are required so that Maven 'runtime'-scoped transitive deps
        // (e.g. com.github.victools:jsonschema-generator declared in kestra core) are included.
        def docGeneratorConfig = project.configurations.create('docGenerator') {
            canBeConsumed = false
            canBeResolved = true
            extendsFrom(project.configurations.named('implementation').get())
            extendsFrom(project.configurations.named('compileOnly').get())
        }

        // Register the generation task
        project.tasks.register('generatePluginDoc', GeneratePluginDocTask) { task ->
            task.description = 'Generates JSON schema & markdown files for each plugin class'
            task.group = 'build'

            task.dependsOn project.tasks.named('classes')

            task.outputDir.set(generatedSchemaDir.get().asFile)
            task.generatorClasspath.from(docGeneratorConfig)
            task.generatorClasspath.from(project.sourceSets.named('main').get().output)

            // Find the plugin JAR that contains DocGenerator
            def pluginJar = PluginDocPlugin.class.protectionDomain.codeSource?.location
            if (pluginJar != null) {
                task.generatorClasspath.from(project.files(new File(pluginJar.toURI())))
            }

            task.inputs.files(project.sourceSets.named('main').get().output)
            task.outputs.dir(generatedSchemaDir)
        }

        // Defer dependency and packagePrefix resolution to afterEvaluate so that
        // consumer build.gradle settings (kestraVersion, jar manifest) are available.
        // we are using by default as prefix the jar manifest X-Kestra-Group, but it can be override by the extension packagePrefix
        project.afterEvaluate {
            def kestraVersion = extension.kestraVersion ?: project.findProperty('kestraVersion')
            if (kestraVersion == null) {
                throw new RuntimeException(
                    'pluginDoc.kestraVersion must be set or a project property ' +
                        '\'kestraVersion\' must be defined'
                )
            }

            project.dependencies.add(
                'docGenerator',
                project.dependencies.enforcedPlatform("io.kestra:platform:${kestraVersion}")
            )

            project.dependencies.add(
                'docGenerator',
                "io.kestra:core:${kestraVersion}"
            )

            // Resolve packagePrefix: extension > jar manifest X-Kestra-Group
            def genTask = project.tasks.named('generatePluginDoc', GeneratePluginDocTask).get()

            def prefix = extension.packagePrefix
            if (!prefix) {
                def jarTask = project.tasks.named('jar', org.gradle.api.tasks.bundling.Jar).get()
                def kestraGroup = jarTask.manifest.attributes.get('X-Kestra-Group')
                if (kestraGroup) {
                    prefix = kestraGroup.toString()
                }
            }

            if (prefix) {
                genTask.packagePrefix.set(prefix)
            }

            genTask.generateJsonSchema.set(extension.generateJsonSchema)
            genTask.generateMarkdown.set(extension.generateMarkdown)
        }

        // Hook into jar task
        project.tasks.named('jar') {
            dependsOn 'generatePluginDoc'
            from(generatedSchemaDir) {
                into 'docs'
            }
        }

        // Hook into shadowJar task if present
        project.pluginManager.withPlugin('com.gradleup.shadow') {
            project.tasks.named('shadowJar') {
                dependsOn 'generatePluginDoc'
                from(generatedSchemaDir) {
                    into 'docs'
                }
            }
        }
    }
}
