package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * Rules over {@code src/main/resources/icons/}: a root {@code plugin-icon.svg} and one
 * {@code <fully-qualified-subpackage>.svg} per subpackage.
 */
class IconRules {

    static List<Rule> rules() {
        return [
            new BaseRule('ICON-001', { PluginModel m ->
                if (m.rootPackage() == null) {
                    return []
                }
                if (!new File(m.iconsDir(), 'plugin-icon.svg').exists()) {
                    return [new Violation('ICON-001', 'icons/plugin-icon.svg',
                        "Missing plugin icon. Add src/main/resources/icons/plugin-icon.svg.")]
                }
                return []
            }),

            new BaseRule('ICON-002', { PluginModel m ->
                String root = m.rootPackage()
                List<Violation> violations = []
                m.packagesWithPlugins().findAll { it != root }.sort().each { pkg ->
                    String fileName = "${pkg}.svg"
                    if (!new File(m.iconsDir(), fileName).exists()) {
                        violations << new Violation('ICON-002', "icons/${fileName}",
                            "Missing icon for subpackage '${pkg}'. Add src/main/resources/icons/${fileName}.")
                    }
                }
                return violations
            })
        ]
    }
}
