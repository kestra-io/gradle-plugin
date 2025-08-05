package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication

class InjectBomVersionsPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.afterEvaluate {
            project.plugins.withId("maven-publish") {
                project.publishing.publications.all { publication ->
                    if (!(publication instanceof MavenPublication)) return

                    publication.pom.withXml { xml ->
                        def root = xml.asNode()

                        def resolvedDeps = project.configurations.compileClasspath
                            .resolvedConfiguration
                            .firstLevelModuleDependencies

                        def dependenciesNode = root.get('dependencies')?.getAt(0) as groovy.util.Node
                        if (dependenciesNode) {
                            dependenciesNode.children().findAll { it instanceof groovy.util.Node }.each { depNode ->
                                def groupId = depNode.get('groupId')?.text()
                                def artifactId = depNode.get('artifactId')?.text()
                                def versionNode = depNode.get('version')

                                if (!versionNode || versionNode.text().trim() == '') {
                                    def resolved = resolvedDeps.find {
                                        it.moduleGroup == groupId && it.moduleName == artifactId
                                    }
                                    if (resolved) {
                                        depNode.appendNode('version', resolved.moduleVersion)
                                        project.logger.lifecycle("🔧 Injected dependency version: ${groupId}:${artifactId}:${resolved.moduleVersion}")
                                    } else {
                                        project.logger.warn("Could not resolve version for ${groupId}:${artifactId}")
                                    }
                                }
                            }
                        }

                        def dmDeps = root.get('dependencyManagement')?.getAt(0)?.get('dependencies')?.getAt(0) as groovy.util.Node
                        if (dmDeps) {
                            dmDeps.children().findAll { it instanceof groovy.util.Node }.each { depNode ->
                                def groupId = depNode.get('groupId')?.text()
                                def artifactId = depNode.get('artifactId')?.text()
                                def versionNode = depNode.get('version')

                                if (!versionNode || versionNode.text().trim() == '') {
                                    def resolved = resolvedDeps.find {
                                        it.moduleGroup == groupId && it.moduleName == artifactId
                                    }
                                    if (resolved) {
                                        depNode.appendNode('version', resolved.moduleVersion)
                                        project.logger.lifecycle("🔧 Injected BOM version: ${groupId}:${artifactId}:${resolved.moduleVersion}")
                                    } else {
                                        project.logger.warn("Could not resolve BOM version for ${groupId}:${artifactId}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
