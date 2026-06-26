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
                // A submodule of a multi-module plugin ships <leaf>.yaml instead of index.yaml
                // (the root index.yaml lives in the core module). Accept either.
                if (!rootMetadataFile(m).exists()) {
                    String leaf = root.tokenize('.').last()
                    return [new Violation('META-001', 'metadata/index.yaml',
                        "Missing root metadata file. Create src/main/resources/metadata/index.yaml (or ${leaf}.yaml for a submodule) for package '${root}'.")]
                }
                return []
            }),

            new BaseRule('META-002', { PluginModel m ->
                String root = m.rootPackage()
                List<Violation> violations = []
                m.packagesWithPlugins().findAll { it != root }.sort().each { pkg ->
                    boolean exists = metadataNames(m, pkg).any { new File(m.metadataDir(), it).exists() }
                    if (!exists) {
                        String fileName = suggestedName(m, pkg)
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
                            "Group '${group}' does not match package '${pkg}'. Set group: ${pkg}.")
                    }
                }
                return violations
            })
        ]
    }

    /** Map each plugin package to the metadata file expected to describe it. */
    static Map<String, File> metadataTargets(PluginModel m) {
        String root = m.rootPackage()
        Map<String, File> targets = [:]
        m.packagesWithPlugins().each { pkg ->
            targets[pkg] = (pkg == root)
                ? rootMetadataFile(m)
                : metadataFileFor(m, pkg)
        }
        return targets
    }

    /**
     * Existing metadata file for a subpackage. A subpackage may be described either by its leaf
     * name ({@code git.yaml}) or by its root-relative dotted path ({@code ee.git.yaml}). The dotted
     * name is preferred when present because it is collision-free: two subpackages can share a leaf
     * segment (e.g. {@code ee.git} and {@code git}) but never a relative path. Falls back to the
     * leaf-named file (which may not exist) so callers that validate existing files skip cleanly
     * when neither is present.
     */
    static File metadataFileFor(PluginModel m, String pkg) {
        String rel = relativeName(m, pkg)
        if (rel != null) {
            File dotted = new File(m.metadataDir(), rel + '.yaml')
            if (dotted.exists()) {
                return dotted
            }
        }
        return new File(m.metadataDir(), leafYaml(pkg))
    }

    /** Accepted metadata filenames for a subpackage: leaf name and the root-relative dotted name. */
    static List<String> metadataNames(PluginModel m, String pkg) {
        List<String> names = [leafYaml(pkg)]
        String rel = relativeName(m, pkg)
        if (rel != null && (rel + '.yaml') != names[0]) {
            names << (rel + '.yaml')
        }
        return names
    }

    /**
     * Filename to suggest when a subpackage has no metadata. Uses the leaf name, falling back to the
     * collision-free dotted name when another plugin subpackage shares the same leaf.
     */
    static String suggestedName(PluginModel m, String pkg) {
        String root = m.rootPackage()
        String leaf = leafYaml(pkg)
        long sharingLeaf = m.packagesWithPlugins().findAll { it != root }.count { leafYaml(it) == leaf }
        if (sharingLeaf > 1) {
            String rel = relativeName(m, pkg)
            if (rel != null) {
                return rel + '.yaml'
            }
        }
        return leaf
    }

    /** Root-relative dotted package name, or null when {@code pkg} is not under the root. */
    static String relativeName(PluginModel m, String pkg) {
        String root = m.rootPackage()
        if (root != null && pkg.startsWith(root + '.')) {
            return pkg.substring(root.length() + 1)
        }
        return null
    }

    /** The root package's metadata file: index.yaml when present, else the leaf-named file. */
    static File rootMetadataFile(PluginModel m) {
        File index = new File(m.metadataDir(), 'index.yaml')
        String root = m.rootPackage()
        if (index.exists() || root == null) {
            return index
        }
        return new File(m.metadataDir(), leafYaml(root))
    }

    /** Metadata file name for a subpackage: the last package segment plus .yaml. */
    static String leafYaml(String pkg) {
        return pkg.tokenize('.').last() + '.yaml'
    }

    private static boolean blank(Object value) {
        return RuleConstants.blank(value)
    }
}
