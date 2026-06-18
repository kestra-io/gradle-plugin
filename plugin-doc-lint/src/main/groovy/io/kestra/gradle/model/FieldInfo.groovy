package io.kestra.gradle.model

/**
 * Extracted view of a single declared field on a task, trigger or output class.
 * Annotation presence/values are read reflectively by name so the plugin needs no
 * compile-time dependency on Kestra.
 */
class FieldInfo {
    String name
    /** The class that declares this field, so an inherited field is reported once at its source. */
    String declaringClassName
    boolean isStatic
    boolean isTransient

    /**
     * True when the field is a serialized, user-facing property (public or has a public getter,
     * not {@code @JsonIgnore}). Internal state fields (e.g. {@code @Getter(AccessLevel.NONE)
     * private final AtomicBoolean}) are not properties and are not subject to @Schema rules.
     */
    boolean isProperty = true

    boolean hasSchema
    String schemaTitle
    String schemaDescription

    boolean hasPluginProperty
    String pluginPropertyGroup
    boolean pluginPropertySecret
}
