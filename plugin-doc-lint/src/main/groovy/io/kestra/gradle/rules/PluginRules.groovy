package io.kestra.gradle.rules

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.ExampleInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.scan.YamlSupport

import java.util.regex.Pattern

/**
 * Rules over {@code @Plugin(examples = ...)} / {@code @Example}.
 *
 * PLUGIN-004 is the only source-based rule: reflection cannot distinguish an explicit
 * {@code lang = "yaml"} from the annotation default (also {@code "yaml"}), so it is checked
 * against the source text instead.
 */
class PluginRules {

    private static final Pattern LANG_YAML = ~/lang\s*=\s*"yaml"/

    static List<Rule> rules() {
        return [
            new BaseRule('PLUGIN-001', { PluginModel m ->
                List<Violation> violations = []
                m.tasksAndTriggers().each { ClassInfo c ->
                    if (c.examples.isEmpty()) {
                        violations << new Violation('PLUGIN-001', c.fqcn,
                            "No @Plugin examples. Add @Plugin(examples = { @Example(full = true, code = ...) }).")
                    }
                }
                return violations
            }),

            new BaseRule('PLUGIN-002', { PluginModel m ->
                List<Violation> violations = []
                eachExample(m) { ClassInfo c, ExampleInfo e, int i ->
                    if (!e.full) {
                        violations << new Violation('PLUGIN-002', "${c.fqcn} (example #${i + 1})",
                            "Example is not full. Set full = true on the @Example.")
                    }
                }
                return violations
            }),

            new BaseRule('PLUGIN-003', { PluginModel m ->
                List<Violation> violations = []
                eachExample(m) { ClassInfo c, ExampleInfo e, int i ->
                    String loc = "${c.fqcn} (example #${i + 1})"
                    Map<String, Object> parsed = YamlSupport.parseMap(e.code)
                    if (parsed == null) {
                        violations << new Violation('PLUGIN-003', loc,
                            "Example code is not valid YAML mapping. Provide a full flow with id, namespace and tasks/triggers.")
                        return
                    }
                    List<String> missing = []
                    if (!parsed.containsKey('id')) missing << 'id'
                    if (!parsed.containsKey('namespace')) missing << 'namespace'
                    if (!parsed.containsKey('tasks') && !parsed.containsKey('triggers')) missing << 'tasks/triggers'
                    if (!missing.isEmpty()) {
                        violations << new Violation('PLUGIN-003', loc,
                            "Example is missing ${missing.join(', ')}. A full example needs id, namespace and tasks or triggers.")
                    }
                }
                return violations
            }),

            new BaseRule('PLUGIN-004', { PluginModel m ->
                List<Violation> violations = []
                m.tasksAndTriggers().findAll { !it.examples.isEmpty() }.each { ClassInfo c ->
                    File source = m.sourceFileFor(c)
                    if (source != null && LANG_YAML.matcher(source.getText('UTF-8')).find()) {
                        violations << new Violation('PLUGIN-004', c.fqcn,
                            "@Example sets lang = \"yaml\" redundantly (it is the default). Remove the lang attribute.")
                    }
                }
                return violations
            }),

            new BaseRule('PLUGIN-005', { PluginModel m ->
                List<Violation> violations = []
                eachExample(m) { ClassInfo c, ExampleInfo e, int i ->
                    Map<String, Object> parsed = YamlSupport.parseMap(e.code)
                    if (parsed == null) {
                        return
                    }
                    collectPlainSecrets(parsed).each { String key ->
                        violations << new Violation('PLUGIN-005', "${c.fqcn} (example #${i + 1})",
                            "Field '${key}' holds a plain-text secret. Use {{ secret('SECRET_NAME') }} instead.")
                    }
                }
                return violations
            })
        ]
    }

    private static void eachExample(PluginModel m, Closure body) {
        m.tasksAndTriggers().each { ClassInfo c ->
            c.examples.eachWithIndex { ExampleInfo e, int i -> body.call(c, e, i) }
        }
    }

    /** Keys whose name looks secret and whose value is a plain literal (no {{ secret(...) }}). */
    private static List<String> collectPlainSecrets(Object node) {
        List<String> found = []
        if (node instanceof Map) {
            node.each { k, v ->
                if (v instanceof String && RuleConstants.looksLikeSecret(k?.toString())) {
                    String value = ((String) v).trim()
                    if (!value.isEmpty() && !value.contains('secret(')) {
                        found << k.toString()
                    }
                }
                found.addAll(collectPlainSecrets(v))
            }
        } else if (node instanceof List) {
            node.each { found.addAll(collectPlainSecrets(it)) }
        }
        return found
    }
}
