package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.scan.YamlSupport

/**
 * Rules over {@code src/main/resources/metadata/*.yaml}.
 *
 * Note on META-003: the issue lists {@code body} among the required non-empty fields, but
 * every current compliant plugin (including plugin-ee-gcp) ships {@code body: ""}. To keep
 * zero false positives, {@code body} must be present but may be empty; group, name, title
 * and description must be non-empty.
 */
class MetadataRules {

    static List<Rule> rules() {
        return [
            new BaseRule('META-001', { PluginModel m ->
                String root = m.rootPackage()
                if (root == null) {
                    return []
                }
                File idx = new File(m.metadataDir(), 'index.yaml')
                if (!idx.exists()) {
                    return [new Violation('META-001', 'metadata/index.yaml',
                        "Missing root metadata file. Create src/main/resources/metadata/index.yaml for package '${root}'.")]
                }
                return []
            }),

            new BaseRule('META-002', { PluginModel m ->
                String root = m.rootPackage()
                List<Violation> violations = []
                m.packagesWithTasksOrTriggers().findAll { it != root }.sort().each { pkg ->
                    String fileName = pkg.tokenize('.').last() + '.yaml'
                    if (!new File(m.metadataDir(), fileName).exists()) {
                        violations << new Violation('META-002', "metadata/${fileName}",
                            "Missing metadata for subpackage '${pkg}'. Create src/main/resources/metadata/${fileName}.")
                    }
                }
                return violations
            }),

            new BaseRule('META-003', { PluginModel m ->
                List<Violation> violations = []
                metadataTargets(m).each { pkg, file ->
                    if (!file.exists()) {
                        return
                    }
                    String rel = "metadata/${file.name}"
                    Map<String, Object> data = YamlSupport.parseMap(file)
                    if (data == null) {
                        violations << new Violation('META-003', rel,
                            "Invalid or empty YAML. Provide non-empty group, name, title, description and a body field.")
                        return
                    }
                    ['group', 'name', 'title', 'description'].each { key ->
                        if (blank(data[key])) {
                            violations << new Violation('META-003', rel,
                                "Field '${key}' is missing or empty. Provide a non-empty '${key}'.")
                        }
                    }
                    if (!data.containsKey('body')) {
                        violations << new Violation('META-003', rel,
                            "Field 'body' is missing. Add a 'body' field (it may be empty).")
                    }
                }
                return violations
            }),

            new BaseRule('META-004', { PluginModel m ->
                List<Violation> violations = []
                metadataTargets(m).each { pkg, file ->
                    if (!file.exists()) {
                        return
                    }
                    Map<String, Object> data = YamlSupport.parseMap(file)
                    if (data == null) {
                        return
                    }
                    String group = data['group']?.toString()
                    if (group != pkg) {
                        violations << new Violation('META-004', "metadata/${file.name}",
                            "group '${group}' does not match package '${pkg}'. Set group: ${pkg}.")
                    }
                }
                return violations
            })
        ]
    }

    /** Map each task/trigger package to the metadata file expected to describe it. */
    static Map<String, File> metadataTargets(PluginModel m) {
        String root = m.rootPackage()
        Map<String, File> targets = [:]
        m.packagesWithTasksOrTriggers().each { pkg ->
            String fileName = (pkg == root) ? 'index.yaml' : pkg.tokenize('.').last() + '.yaml'
            targets[pkg] = new File(m.metadataDir(), fileName)
        }
        return targets
    }

    private static boolean blank(Object value) {
        return value == null || value.toString().trim().isEmpty()
    }
}
