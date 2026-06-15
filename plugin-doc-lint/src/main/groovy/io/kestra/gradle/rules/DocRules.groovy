package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.scan.YamlSupport

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Rules over {@code src/main/resources/doc/}: the root how-to and any markdown referenced
 * from metadata.
 */
class DocRules {

    private static final int MIN_LINES = 10
    private static final Pattern MD_REF = ~/[\w.\/-]+\.md/

    static List<Rule> rules() {
        return [
            new BaseRule('DOC-001', { PluginModel m ->
                String root = m.rootPackage()
                if (root == null) {
                    return []
                }
                // Only the module that owns the root (ships index.yaml) carries the root how-to.
                // Submodules of a multi-module plugin keep it in the core module.
                if (!new File(m.metadataDir(), 'index.yaml').exists()) {
                    return []
                }
                String fileName = "${root}.md"
                if (!new File(m.docDir(), fileName).exists()) {
                    return [new Violation('DOC-001', "doc/${fileName}",
                        "Missing root how-to. Add src/main/resources/doc/${fileName}.")]
                }
                return []
            }),

            new BaseRule('DOC-002', { PluginModel m ->
                List<Violation> violations = []
                referencedMarkdown(m).each { String ref ->
                    if (!new File(m.docDir(), ref).exists()) {
                        violations << new Violation('DOC-002', "doc/${ref}",
                            "Markdown '${ref}' is referenced in metadata but missing from doc/. Create it.")
                    }
                }
                return violations
            }),

            new BaseRule('DOC-003', { PluginModel m ->
                List<Violation> violations = []
                Set<String> mdFiles = []
                String root = m.rootPackage()
                if (root != null) {
                    mdFiles << "${root}.md".toString()
                }
                mdFiles.addAll(referencedMarkdown(m))
                mdFiles.sort().each { String name ->
                    File md = new File(m.docDir(), name)
                    if (md.exists() && md.readLines().size() < MIN_LINES) {
                        violations << new Violation('DOC-003', "doc/${name}",
                            "How-to is too short (< ${MIN_LINES} lines). Expand it.")
                    }
                }
                return violations
            })
        ]
    }

    /** Markdown filenames referenced from any metadata/*.yaml string value. */
    private static Set<String> referencedMarkdown(PluginModel m) {
        Set<String> refs = []
        File dir = m.metadataDir()
        if (!dir.exists()) {
            return refs
        }
        dir.listFiles({ File f -> f.name.endsWith('.yaml') } as FileFilter)?.each { File yaml ->
            Map<String, Object> data = YamlSupport.parseMap(yaml)
            if (data != null) {
                collectStrings(data).each { String value ->
                    Matcher matcher = MD_REF.matcher(value)
                    while (matcher.find()) {
                        refs << new File(matcher.group()).name
                    }
                }
            }
        }
        return refs
    }

    private static List<String> collectStrings(Object node) {
        List<String> strings = []
        if (node instanceof String) {
            strings << (String) node
        } else if (node instanceof Map) {
            node.values().each { strings.addAll(collectStrings(it)) }
        } else if (node instanceof List) {
            node.each { strings.addAll(collectStrings(it)) }
        }
        return strings
    }
}
