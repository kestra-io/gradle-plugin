package io.kestra.gradle

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.rules.Rule
import io.kestra.gradle.rules.Rules
import io.kestra.gradle.scan.ClassScanner
import io.kestra.gradle.scan.YamlSupport
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Builds the {@link PluginModel} from the compiled output and resources, runs every enabled
 * rule, prints a grouped report, and fails the build on any violation unless ignored.
 */
abstract class LintPluginDocsTask extends DefaultTask {

    @Classpath
    abstract ConfigurableFileCollection getPluginClasspath()

    @InputFiles
    abstract ConfigurableFileCollection getClassesDirs()

    @Internal
    abstract Property<File> getResourcesDir()

    @Internal
    abstract Property<File> getSourceDir()

    /**
     * The resource and source file trees, tracked so that editing metadata, icons, docs or
     * source (not just recompiling classes) re-runs the lint. The roots above are kept
     * {@code @Internal} for path resolution.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    abstract ConfigurableFileCollection getDocInputs()

    @Input
    abstract SetProperty<String> getDisabledRules()

    @Input
    abstract Property<Boolean> getIgnoreFailures()

    @TaskAction
    void lint() {
        List<File> classDirs = classesDirs.files as List
        PluginModel model = buildModel(classDirs)

        if (model.classes.isEmpty()) {
            // All classes failing to load means a broken classpath, not an empty plugin: fail rather than pass silently.
            if (model.skippedClasses > 0) {
                throw new GradleException("plugin-doc-lint loaded no classes but skipped ${model.skippedClasses} that failed to load. " +
                    "Check that the plugin's compile/runtime classpath resolves (e.g. kestra-core).")
            }
            logger.lifecycle('[plugin-doc-lint] No compiled classes found; nothing to lint.')
            return
        }

        Set<String> disabled = disabledRules.get()
        List<Rule> rules = Rules.all().findAll { !disabled.contains(it.id()) }

        List<Violation> violations = []
        rules.each { violations.addAll(it.check(model)) }

        if (violations.isEmpty()) {
            logger.lifecycle("[plugin-doc-lint] All ${rules.size()} rules passed.")
            return
        }

        report(violations)

        if (!ignoreFailures.get()) {
            throw new GradleException("plugin-doc-lint found ${violations.size()} documentation violation(s). See the report above.")
        }
        logger.warn('[plugin-doc-lint] ignoreFailures is set; not failing the build.')
    }

    protected PluginModel buildModel(List<File> classDirs) {
        List<URL> urls = []
        classDirs.each { urls << it.toURI().toURL() }
        pluginClasspath.files.each { urls << it.toURI().toURL() }

        URLClassLoader loader = new URLClassLoader(urls as URL[], ClassLoader.getPlatformClassLoader())
        try {
            ClassScanner.Result scan = new ClassScanner(loader).scan(classDirs)
            if (scan.skipped > 0) {
                logger.warn("[plugin-doc-lint] Skipped ${scan.skipped} class(es) that failed to load; they were not linted.")
            }
            File resources = resourcesDir.getOrNull()
            return new PluginModel(
                classes: scan.classes,
                packages: scan.packages,
                resourceRoot: resources,
                sourceRoot: sourceDir.getOrNull(),
                declaredRootPackage: declaredRoot(resources),
                skippedClasses: scan.skipped
            )
        } finally {
            loader.close()
        }
    }

    protected static String declaredRoot(File resources) {
        if (resources == null) {
            return null
        }
        Map<String, Object> index = YamlSupport.parseMap(new File(resources, 'metadata/index.yaml'))
        return index?.get('group')?.toString()
    }

    protected void report(List<Violation> violations) {
        logger.error("\n[plugin-doc-lint] ${violations.size()} documentation violation(s):\n")
        violations.groupBy { it.ruleId }.sort().each { String ruleId, List<Violation> group ->
            logger.error("  ${ruleId}:")
            group.each { Violation v ->
                logger.error("    - ${v.location}: ${v.message}")
            }
        }
        logger.error('')
    }
}
