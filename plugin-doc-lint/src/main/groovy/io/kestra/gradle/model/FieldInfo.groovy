package io.kestra.gradle.model

/**
 * Extracted view of a single declared field on a task, trigger or output class.
 * Annotation presence/values are read reflectively by name so the plugin needs no
 * compile-time dependency on Kestra.
 */
class FieldInfo {
    String name
    boolean isStatic
    boolean isTransient

    boolean hasSchema
    String schemaTitle
    String schemaDescription

    boolean hasPluginProperty
    String pluginPropertyGroup
    boolean pluginPropertySecret
}
