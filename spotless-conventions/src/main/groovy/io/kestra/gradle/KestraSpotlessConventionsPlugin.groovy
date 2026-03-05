package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Copy
import com.diffplug.gradle.spotless.SpotlessExtension
import java.security.MessageDigest

class KestraSpotlessConventionsPlugin implements Plugin<Project> {

    private static String computeChecksum(URL url) {
        if (url == null) return ''
        return MessageDigest.getInstance('MD5')
                .digest(url.getText('UTF-8').getBytes('UTF-8'))
                .encodeHex().toString()
    }

    @Override
    void apply(Project project) {
        project.plugins.apply('com.diffplug.spotless')
        ClassLoader pluginClassLoader = this.getClass().getClassLoader()

        def extractSpotlessConfig = project.tasks.register('extractSpotlessConfig') { task ->
            File spotlessDir = project.layout.buildDirectory.dir('spotless-config').get().asFile
            task.outputs.dir(spotlessDir)

            task.doLast {
                ['eclipse-kestra.importorder', 'eclipse-java-kestra-style.xml'].each { fileName ->
                    URL resource = pluginClassLoader.getResource("spotless/${fileName}")
                    if (resource != null) {
                        File target = new File(spotlessDir, fileName)
                        target.parentFile.mkdirs()
                        target.text = resource.getText('UTF-8')
                    }
                }
            }
        }

        project.tasks.register('setupHooks', Exec) { task ->
            task.group = 'development'
            File hooksDir = project.file('.github/.hooks')
            File markerFile = project.layout.buildDirectory.file('setupHooks/.setup_hooks_done').get().asFile

            task.outputs.dir(hooksDir)
            task.outputs.file(markerFile)

            task.inputs.property('hooksChecksum', project.providers.provider {
                ['setup_hooks.sh', 'pre-commit'].collect {
                    computeChecksum(pluginClassLoader.getResource("spotless/git-hooks/${it}"))
                }.join(':')
            })

            if (project.rootProject.file('.git').exists()) {
                task.commandLine('sh', '-c', 'chmod +x .github/.hooks/setup_hooks.sh .github/.hooks/pre-commit && ./.github/.hooks/setup_hooks.sh')
            } else {
                task.enabled = false
            }

            task.doFirst {
                hooksDir.mkdirs()
                ['setup_hooks.sh', 'pre-commit'].each { hookName ->
                    URL url = pluginClassLoader.getResource("spotless/git-hooks/${hookName}")
                    if (url != null) {
                        File target = new File(hooksDir, hookName)
                        target.text = url.getText('UTF-8')
                        target.setExecutable(true)
                    }
                }
            }

            task.doLast {
                markerFile.text = "completed at ${new Date()}"
            }
        }

        project.tasks.named('build') { it.dependsOn('setupHooks') }

        project.extensions.configure(SpotlessExtension) { ext ->
            ext.enforceCheck = false
            ext.java { javaSpec ->
                javaSpec.target('src/**/*.java')

                File spotlessDir = project.layout.buildDirectory.dir('spotless-config').get().asFile
                javaSpec.importOrderFile(new File(spotlessDir, 'eclipse-kestra.importorder'))
                javaSpec.eclipse().configFile(new File(spotlessDir, 'eclipse-java-kestra-style.xml'))

                javaSpec.toggleOffOn()
                javaSpec.removeUnusedImports()
            }
        }

        project.tasks.matching { it.name.startsWith('spotless') }.configureEach {
            it.dependsOn(extractSpotlessConfig)
        }
    }
}