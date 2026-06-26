package io.kestra.gradle.model

/**
 * Extracted view of one compiled class in the plugin output. {@code kind} tells whether
 * the class is a task, trigger, output, or unrelated, based on its type hierarchy.
 */
class ClassInfo {
    enum Kind { TASK, TRIGGER, TASK_RUNNER, LOG_EXPORTER, OUTPUT, OTHER }

    String fqcn
    String packageName
    String simpleName
    boolean isAbstract
    boolean isInterface
    Kind kind = Kind.OTHER

    boolean hasSchema
    String schemaTitle
    String schemaDescription

    boolean hasPluginAnnotation
    // @Plugin(internal = true): resolvable through the registry but not a user-facing catalog entry,
    // so it is exempt from documentation rules (no examples/title/description required).
    boolean internal
    // @Deprecated classes are being phased out, so they are exempt from documentation rules: a
    // deprecated alias kept for backward compatibility should not have to meet current doc standards.
    boolean deprecated
    List<ExampleInfo> examples = []

    List<FieldInfo> fields = []

    boolean isTaskOrTrigger() {
        return kind == Kind.TASK || kind == Kind.TRIGGER
    }

    /** Concrete (registrable) task or trigger: abstract bases and internal helpers are not plugin entry points. */
    boolean isConcreteTaskOrTrigger() {
        return isTaskOrTrigger() && !isAbstract && !isInterface && !internal && !deprecated
    }

    /** Any documentable plugin entry point: task, trigger, task runner or log exporter. */
    boolean isDocumentablePlugin() {
        return (kind == Kind.TASK || kind == Kind.TRIGGER
            || kind == Kind.TASK_RUNNER || kind == Kind.LOG_EXPORTER) && !isAbstract && !isInterface && !internal && !deprecated
    }

    /** Concrete output class (a task/trigger result type). */
    boolean isConcreteOutput() {
        return kind == Kind.OUTPUT && !isAbstract && !isInterface && !internal && !deprecated
    }
}
