package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import com.diffplug.gradle.spotless.SpotlessExtension

class KestraSpotlessConventionsPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.plugins.apply('com.diffplug.spotless')

        ClassLoader pluginClassLoader = this.getClass().getClassLoader()

        project.tasks.register('setupHooks', Exec) { Exec task ->
            task.group = 'development'
            task.description = 'Set up Git hooks for the project'

            // Decide activation at configuration time to avoid changing task.enabled while the task is executing
            File gitDir = project.rootProject.file('.git')
            boolean gitExists = gitDir.exists()
            if (gitExists) {
                project.logger.info('[KestraSpotless] .git directory found; will activate hooks')
                task.commandLine('sh', '-c', 'chmod +x .github/.hooks/setup_hooks.sh .github/.hooks/pre-commit && ./.github/.hooks/setup_hooks.sh')
            } else {
                // Log at configuration time so tests and builds see the message and the Exec task is skipped safely
                project.logger.lifecycle('[KestraSpotless] No .git directory found; skipping hook activation to avoid executing git-related commands in non-git projects')
                task.enabled = false
            }

            task.doFirst {
                File hooksDir = project.file('.github/.hooks')
                if (!hooksDir.exists()) {
                    hooksDir.mkdirs()
                }

                ['setup_hooks.sh', 'pre-commit'].each { hookName ->
                    String resourcePath = "spotless/git-hooks/${hookName}"
                    URL hookUrl = pluginClassLoader.getResource(resourcePath)
                    project.logger.info("[KestraSpotless] Resource: ${resourcePath} found at: ${hookUrl}")
                    if (hookUrl != null) {
                        String hookContent = hookUrl.getText('UTF-8')
                        File targetFile = project.file(".github/.hooks/${hookName}")
                        project.logger.info("[KestraSpotless] Writing hook to: ${targetFile.absolutePath}")
                        targetFile.write(hookContent)
                        targetFile.setExecutable(true)
                    } else {
                        project.logger.warn("[KestraSpotless] Resource ${resourcePath} not found or empty!")
                    }
                }
            }

        }

        project.tasks.named('build') { it.dependsOn('setupHooks') }

        project.extensions.configure(SpotlessExtension) { ext ->
            ext.enforceCheck = false

            ext.java { javaSpec ->
                Object targetPath = project.findProperty('targetFile')
                if (targetPath instanceof String && !targetPath.toString().trim().isEmpty()) {
                    javaSpec.target(project.file(targetPath.toString()))
                } else {
                    javaSpec.target('src/**/*.java')
                }

                URL importOrderUrl = pluginClassLoader.getResource('spotless/eclipse-kestra.importorder')
                URL eclipseUrl = pluginClassLoader.getResource('spotless/eclipse-java-kestra-style.xml')

                if (importOrderUrl != null) {
                    String importOrder = importOrderUrl.getText('UTF-8')
                    File importOrderFile = project.layout.buildDirectory.dir('spotless').get().file('spotless/eclipse-kestra.importorder').asFile
                    importOrderFile.parentFile.mkdirs()
                    importOrderFile.write(importOrder)
                    javaSpec.importOrderFile(importOrderFile)
                } else {
                    project.logger.warn('[KestraSpotless] eclipse-kestra.importorder resource not found')
                }

                if (eclipseUrl != null) {
                    String eclipseConfig = eclipseUrl.getText('UTF-8')
                    File eclipseConfigFile = project.layout.buildDirectory.dir('spotless').get().file('spotless/eclipse-java-kestra-style.xml').asFile
                    eclipseConfigFile.parentFile.mkdirs()
                    eclipseConfigFile.write(eclipseConfig)
                    javaSpec.eclipse().configFile(eclipseConfigFile)
                } else {
                    project.logger.warn('[KestraSpotless] eclipse-java-kestra-style.xml resource not found')
                }

                javaSpec.toggleOffOn()
                javaSpec.removeUnusedImports()
            }
        }
    }
}
