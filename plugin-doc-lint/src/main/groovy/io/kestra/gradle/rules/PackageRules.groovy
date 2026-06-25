package io.kestra.gradle.rules

import io.kestra.gradle.model.PackageInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * Rules over package structure: {@code package-info.java}, {@code @PluginSubGroup} and the
 * root-vs-subpackage placement of tasks.
 */
class PackageRules {

    static List<Rule> rules() {
        return [
            new BaseRule('PKG-001', { PluginModel m ->
                List<Violation> violations = []
                m.packagesWithPlugins().sort().each { pkg ->
                    PackageInfo info = m.packages[pkg]
                    if (info == null || !info.hasPackageInfo) {
                        violations << new Violation('PKG-001', pkg,
                            "Package has no package-info.java. Add one annotated with @PluginSubGroup.")
                    } else if (!info.hasSubGroup) {
                        violations << new Violation('PKG-001', "${pkg}.package-info",
                            "package-info.java is missing @PluginSubGroup. Add @PluginSubGroup(...).")
                    }
                }
                return violations
            }),

            new BaseRule('PKG-002', { PluginModel m ->
                List<Violation> violations = []
                m.packagesWithPlugins().sort().each { pkg ->
                    PackageInfo info = m.packages[pkg]
                    if (info == null || !info.hasSubGroup) {
                        return
                    }
                    info.subGroupCategories.findAll { !RuleConstants.CATEGORIES.contains(it) }.each { category ->
                        violations << new Violation('PKG-002', "${pkg}.package-info",
                            "@PluginSubGroup category '${category}' is not allowed. Use one of ${RuleConstants.CATEGORIES.sort()}.")
                    }
                }
                return violations
            }),

            new BaseRule('PKG-003', { PluginModel m ->
                String root = m.rootPackage()
                // Only tasks/triggers count here (matching the rule's wording): a root-level log
                // exporter or task runner is not "mixed task placement".
                Set<String> pkgs = m.packagesWithTasksOrTriggers()
                if (root != null && pkgs.size() > 1 && pkgs.contains(root)) {
                    return [new Violation('PKG-003', root,
                        "Tasks/triggers are placed in both the root package and subpackages. Move root-level tasks into a subpackage.")]
                }
                return []
            })
        ]
    }
}
