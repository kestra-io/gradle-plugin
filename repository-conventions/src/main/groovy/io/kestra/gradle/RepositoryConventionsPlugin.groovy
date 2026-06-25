package io.kestra.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class RepositoryConventionsPlugin implements Plugin<Settings> {

    private static final String GCP_MAVEN_REMOTE_URL = "https://europe-maven.pkg.dev/kestra-host/maven-remote"

    @Override
    void apply(Settings settings) {
        def token = System.getenv("MAVEN_REMOTE_TOKEN")
        if (!token) {
            return
        }

        settings.gradle.allprojects { project ->
            project.afterEvaluate {
                // ponytail: remove+addFirst because repositories.maven() appends; proxy must be first to avoid 429s on Maven Central
                def repo = project.repositories.maven {
                    url = GCP_MAVEN_REMOTE_URL
                    credentials {
                        username = "oauth2accesstoken"
                        password = token
                    }
                }
                project.repositories.remove(repo)
                project.repositories.addFirst(repo)
            }
        }
    }
}
