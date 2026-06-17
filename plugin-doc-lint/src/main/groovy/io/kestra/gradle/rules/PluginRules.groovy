package io.kestra.gradle.rules

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.ExampleInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.scan.YamlSupport

import java.util.regex.Matcher
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
    private static final Pattern PLUGIN_ANNOTATION = ~/@Plugin\s*\(/

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
                    if (source != null && pluginAnnotationBlocks(source.getText('UTF-8')).any { LANG_YAML.matcher(it).find() }) {
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

    /**
     * Text of each {@code @Plugin(...)} annotation block, matched by balancing parentheses from
     * the opening paren. Scoping the lang search to these blocks keeps PLUGIN-004 from matching a
     * stray {@code lang = "yaml"} in a comment, a local, or an unrelated annotation in the file.
     */
    private static List<String> pluginAnnotationBlocks(String source) {
        List<String> blocks = []
        Matcher matcher = PLUGIN_ANNOTATION.matcher(source)
        while (matcher.find()) {
            int open = matcher.end() - 1
            int depth = 0
            int i = open
            while (i < source.length()) {
                String ch = source.substring(i, i + 1)
                if (ch == '(') {
                    depth++
                } else if (ch == ')') {
                    depth--
                    if (depth == 0) {
                        i++
                        break
                    }
                }
                i++
            }
            blocks << source.substring(open, Math.min(i, source.length()))
        }
        return blocks
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
                    // Only a hardcoded literal is a plain-text secret. Any templated value
                    // ({{ secret('X') }}, {{ inputs.x }}, ...) is not plain text.
                    if (!value.isEmpty() && !value.contains('{{')) {
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
