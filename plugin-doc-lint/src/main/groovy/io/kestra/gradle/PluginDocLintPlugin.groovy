package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * Registers the {@code lintPluginDocs} task and wires it into {@code check}. Plugin repos
 * apply it with {@code id 'io.kestra.gradle.plugin-doc-lint'} and no further configuration.
 */
class PluginDocLintPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        PluginDocLintExtension extension = project.extensions.create('pluginDocLint', PluginDocLintExtension)

        def lintTask = project.tasks.register('lintPluginDocs', LintPluginDocsTask) { task ->
            task.group = 'verification'
            task.description = 'Lints Kestra plugin documentation (metadata, icons, @Schema, @Plugin examples, secrets).'
            task.disabledRules.set(project.provider { extension.disabledRules })
            task.ignoreFailures.set(project.provider { extension.ignoreFailures })
        }

        project.plugins.withId('java') {
            SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
            SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

            lintTask.configure { task ->
                task.dependsOn(main.classesTaskName)
                // compileClasspath carries compileOnly deps (kestra-core, swagger) needed to
                // resolve base types and annotations; runtimeClasspath covers the rest.
                task.pluginClasspath.from(main.compileClasspath)
                task.pluginClasspath.from(main.runtimeClasspath)
                task.classesDirs.from(main.output.classesDirs)
                task.resourcesDir.set(firstOrDefault(main.resources.srcDirs, project.file('src/main/resources')))
                task.sourceDir.set(firstOrDefault(main.java.srcDirs, project.file('src/main/java')))
            }

            project.tasks.named('check').configure { it.dependsOn(lintTask) }
        }
    }

    private static File firstOrDefault(Set<File> dirs, File fallback) {
        return dirs == null || dirs.isEmpty() ? fallback : dirs.iterator().next()
    }
}
