package io.kestra.gradle.rules

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.FieldInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * Rules over property annotations on task and trigger fields.
 */
class PropertyRules {

    static List<Rule> rules() {
        return [
            new BaseRule('PROP-001', { PluginModel m ->
                List<Violation> violations = []
                // Input concern: a group organizes the input form, so it is meaningless on output
                // result fields. Scope to task/trigger inputs only, like PROP-002 and PROP-003.
                m.documentablePlugins().each { ClassInfo c ->
                    c.fields.findAll { it.hasPluginProperty }.each { FieldInfo f ->
                        if (!RuleConstants.PROPERTY_GROUPS.contains(f.pluginPropertyGroup)) {
                            violations << new Violation('PROP-001', "${c.fqcn}#${f.name}",
                                "@PluginProperty group '${f.pluginPropertyGroup ?: ''}' is not allowed. Use one of ${RuleConstants.PROPERTY_GROUPS.sort()}.")
                        }
                    }
                }
                return violations
            }),

            new BaseRule('PROP-002', { PluginModel m ->
                List<Violation> violations = []
                // Secret masking is an input concern: check plugin inputs, not output results.
                m.documentablePlugins().each { ClassInfo c ->
                    c.fields.findAll { !it.isStatic && it.isProperty }.each { FieldInfo f ->
                        if (RuleConstants.looksLikeSecret(f.name) && !f.pluginPropertySecret) {
                            violations << new Violation('PROP-002', "${c.fqcn}#${f.name}",
                                "Secret-looking field is not marked secret. Add @PluginProperty(secret = true).")
                        }
                    }
                }
                return violations
            }),

            new BaseRule('PROP-003', { PluginModel m ->
                List<Violation> violations = []
                // Input concern only: 'version' collides with the reserved task/trigger version key
                // in flow YAML. Output result fields named 'version' are fine, so outputs are excluded.
                m.documentablePlugins().each { ClassInfo c ->
                    c.fields.findAll { !it.isStatic && it.isProperty }.each { FieldInfo f ->
                        if (f.name == 'version') {
                            violations << new Violation('PROP-003', "${c.fqcn}#${f.name}",
                                "Property named 'version' conflicts with Kestra internals. Rename it.")
                        }
                    }
                }
                return violations
            })
        ]
    }
}
