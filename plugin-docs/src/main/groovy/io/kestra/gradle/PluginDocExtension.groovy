package io.kestra.gradle

/**
 * Extension for configuring the Kestra Plugin Doc extension.
 */
class PluginDocExtension {
    /**
     * The version of Kestra core to use.
     * If not set, falls back to the project property 'kestraVersion'.
     */
    String kestraVersion

    /**
     * Package prefix to filter which plugin classes to generate.
     * Defaults to the JAR manifest attribute 'X-Kestra-Group' if not set.
     */
    String packagePrefix

    /**
     * Does we generate JSON schema files
     */
    Boolean generateJsonSchema = true

    /**
     * Does we generate markdown files
     */
    Boolean generateMarkdown = true
}
