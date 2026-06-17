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
                // Scoped to task/trigger/output classes: group is meaningful on a task's own
                // properties, not on nested model POJOs used merely as property types.
                propertyHolders(m).each { ClassInfo c ->
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
                propertyHolders(m).each { ClassInfo c ->
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

    private static List<ClassInfo> propertyHolders(PluginModel m) {
        List<ClassInfo> holders = new ArrayList<>(m.documentablePlugins())
        holders.addAll(m.classes.findAll { it.isConcreteOutput() })
        return holders
    }
}
