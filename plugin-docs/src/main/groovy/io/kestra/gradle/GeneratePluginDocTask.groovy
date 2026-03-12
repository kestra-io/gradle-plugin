package io.kestra.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

import javax.inject.Inject

/**
 * Gradle task that generates Docs for Kestra plugin classes.
 * <p>
 * Uses a forked JVM process with the consumer project's classpath to run
 * {@link DocGenerator}, which scans plugins via the
 * Kestra {@code DefaultPluginRegistry} and generates JSON schemas, Markdown for plugins.
 */
abstract class GeneratePluginDocTask extends DefaultTask {

    @Inject
    abstract ExecOperations getExecOperations()

    /**
     * Output directory for generated JSON schema files.
     */
    @OutputDirectory
    abstract Property<File> getOutputDir()

    /**
     * Package prefix to filter which plugin classes to generate schemas for.
     */
    @Input
    abstract Property<String> getPackagePrefix()

    /**
     * Does we generate JSON schema files
     */
    @Input
    @Optional
    abstract Property<Boolean> getGenerateJsonSchema()

    /**
     * Does we generate markdown files
     */
    @Input
    @Optional
    abstract Property<Boolean> getGenerateMarkdown()

    /**
     * The full classpath used to run the doc generator,
     * including the consumer project's main output and io.kestra:core.
     */
    @Classpath
    abstract ConfigurableFileCollection getGeneratorClasspath()

    @TaskAction
    void generate() {
        def outDir = outputDir.get()
        outDir.mkdirs()

        execOperations.javaexec {
            mainClass.set('io.kestra.gradle.DocGenerator')
            classpath = generatorClasspath
            args outDir.absolutePath, packagePrefix.get(), generateJsonSchema.get(), generateMarkdown.get()
        }
    }
}
