package io.kestra.gradle.rules

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.FieldInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * Rules over {@code @Schema} (io.swagger) annotations on task, trigger and output classes.
 */
class SchemaRules {

    static List<Rule> rules() {
        return [
            new BaseRule('SCHEMA-001', { PluginModel m ->
                List<Violation> violations = []
                m.tasksAndTriggers().each { ClassInfo c ->
                    if (!c.hasSchema) {
                        violations << new Violation('SCHEMA-001', c.fqcn,
                            "Task/trigger class is missing @Schema. Add @Schema(title = \"...\", description = \"...\").")
                    }
                }
                return violations
            }),

            new BaseRule('SCHEMA-002', { PluginModel m ->
                List<Violation> violations = []
                m.tasksAndTriggers().each { ClassInfo c ->
                    if (c.hasSchema && blank(c.schemaDescription)) {
                        violations << new Violation('SCHEMA-002', c.fqcn,
                            "Class-level @Schema has no description. Add a non-empty description.")
                    }
                }
                return violations
            }),

            new BaseRule('SCHEMA-003', { PluginModel m ->
                List<Violation> violations = []
                m.tasksAndTriggers().each { ClassInfo c ->
                    documentedFields(c).each { FieldInfo f ->
                        if (!f.hasSchema) {
                            violations << new Violation('SCHEMA-003', "${c.fqcn}#${f.name}",
                                "Field is missing @Schema. Annotate it with @Schema(title = \"...\").")
                        }
                    }
                }
                return violations
            }),

            new BaseRule('SCHEMA-004', { PluginModel m ->
                List<Violation> violations = []
                outputs(m).each { ClassInfo c ->
                    documentedFields(c).each { FieldInfo f ->
                        if (!f.hasSchema) {
                            violations << new Violation('SCHEMA-004', "${c.fqcn}#${f.name}",
                                "Output field is missing @Schema. Annotate it with @Schema(title = \"...\").")
                        }
                    }
                }
                return violations
            }),

            new BaseRule('SCHEMA-005', { PluginModel m ->
                List<Violation> violations = []
                (m.tasksAndTriggers() + outputs(m)).unique().each { ClassInfo c ->
                    if (endsWithPeriod(c.schemaTitle)) {
                        violations << new Violation('SCHEMA-005', c.fqcn,
                            "@Schema title ends with a period. Remove the trailing '.'.")
                    }
                    documentedFields(c).each { FieldInfo f ->
                        if (endsWithPeriod(f.schemaTitle)) {
                            violations << new Violation('SCHEMA-005', "${c.fqcn}#${f.name}",
                                "@Schema title ends with a period. Remove the trailing '.'.")
                        }
                    }
                }
                return violations
            })
        ]
    }

    static List<ClassInfo> outputs(PluginModel m) {
        return m.classes.findAll { it.kind == ClassInfo.Kind.OUTPUT && !it.isAbstract && !it.isInterface }
    }

    private static List<FieldInfo> documentedFields(ClassInfo c) {
        return c.fields.findAll { !it.isStatic && !it.isTransient }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty()
    }

    private static boolean endsWithPeriod(String value) {
        return value != null && value.trim().endsWith('.')
    }
}
