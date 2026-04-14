package io.kestra.gradle

import org.gradle.api.initialization.Settings
import org.gradle.api.Plugin
import org.gradle.api.tasks.testing.Test

class DevelocityConventionsPlugin implements Plugin<Settings> {

    void apply(Settings settings) {
        settings.pluginManager.apply('com.gradle.develocity')
        settings.pluginManager.apply('com.gradle.common-custom-user-data-gradle-plugin')

        def isCI = System.getenv('CI') != null

        settings.buildCache {
            remote(settings.develocity.buildCache) {
                enabled = true
                push = isCI
            }
        }

        settings.develocity {
            server = "https://develocity.kestra.io"
            buildScan {
                uploadInBackground = !isCI
                publishing.onlyIf { it.authenticated }
                buildScanPublished { scan ->
                    if (isCI) {
                        def payload = [
                            timestamp   : new Date().toString(),
                            taskNames   : settings.gradle.startParameter.taskNames,
                            buildScanId : scan.buildScanId,
                            buildScanUri: scan.buildScanUri.toString()
                        ]
                        settings.rootDir.toPath()
                            .resolve("develocity-scan-output.ndjson")
                            .toFile() << groovy.json.JsonOutput.toJson(payload) + System.lineSeparator()
                    }
                }
            }
        }

        // Record each test task's max heap size as a build scan custom value
        settings.gradle.allprojects { project ->
            project.tasks.withType(Test).configureEach { t ->
                t.doFirst {
                    settings.develocity.buildScan.value("${t.path}#maxHeapSize", t.maxHeapSize)
                }
            }
        }
    }
}
