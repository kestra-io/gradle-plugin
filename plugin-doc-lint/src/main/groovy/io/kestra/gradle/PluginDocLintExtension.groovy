package io.kestra.gradle

/**
 * Configuration for the doc-lint plugin.
 *
 * <pre>
 * pluginDocLint {
 *     disabledRules = ['DOC-001', 'DOC-003']
 *     ignoreFailures = false
 * }
 * </pre>
 */
class PluginDocLintExtension {
    /** Rule ids to skip entirely, e.g. {@code 'META-003'}. */
    Set<String> disabledRules = [] as Set

    /** When true, violations are reported but do not fail the build. */
    boolean ignoreFailures = false
}
