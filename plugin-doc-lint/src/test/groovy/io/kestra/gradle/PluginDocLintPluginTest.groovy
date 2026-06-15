package io.kestra.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * End-to-end tests via Gradle TestKit. The test project ships stub Kestra annotations and
 * base interfaces under the real Kestra FQNs, so the reflection scanner exercises the full
 * path without any external dependency.
 */
class PluginDocLintPluginTest {

    @TempDir
    Path testProjectDir
    File buildFile
    File settingsFile

    @BeforeEach
    void setup() {
        buildFile = testProjectDir.resolve('build.gradle').toFile()
        settingsFile = testProjectDir.resolve('settings.gradle').toFile()
        settingsFile.text = "rootProject.name = 'test-plugin'"
        writeKestraStubs()
    }

    private File file(String relative) {
        File f = testProjectDir.resolve(relative).toFile()
        f.parentFile.mkdirs()
        return f
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments(args)
    }

    private void applyPlugin(String extra = '') {
        buildFile.text = """
            plugins {
                id 'java'
                id 'io.kestra.gradle.plugin-doc-lint'
            }
            repositories { mavenCentral() }
            ${extra}
        """
    }

    // Stub Kestra/Swagger annotations and base types under their real FQNs.
    private void writeKestraStubs() {
        file('src/main/java/io/kestra/core/models/tasks/Task.java').text =
            'package io.kestra.core.models.tasks; public interface Task {}'
        file('src/main/java/io/kestra/core/models/tasks/Output.java').text =
            'package io.kestra.core.models.tasks; public interface Output {}'
        file('src/main/java/io/swagger/v3/oas/annotations/media/Schema.java').text = '''
            package io.swagger.v3.oas.annotations.media;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
            public @interface Schema { String title() default ""; String description() default ""; }
        '''.stripIndent()
        file('src/main/java/io/kestra/core/models/annotations/Example.java').text = '''
            package io.kestra.core.models.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
            public @interface Example { String title() default ""; String[] code() default ""; String lang() default "yaml"; boolean full() default false; }
        '''.stripIndent()
        file('src/main/java/io/kestra/core/models/annotations/Plugin.java').text = '''
            package io.kestra.core.models.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
            public @interface Plugin { Example[] examples() default {}; }
        '''.stripIndent()
        file('src/main/java/io/kestra/core/models/annotations/PluginProperty.java').text = '''
            package io.kestra.core.models.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface PluginProperty { String group() default ""; boolean secret() default false; }
        '''.stripIndent()
        file('src/main/java/io/kestra/core/models/annotations/PluginSubGroup.java').text = '''
            package io.kestra.core.models.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PACKAGE)
            public @interface PluginSubGroup {
                PluginCategory[] categories() default { PluginCategory.CORE };
                enum PluginCategory { AI, BUSINESS, CLOUD, CORE, DATA, INFRASTRUCTURE, TOOL }
            }
        '''.stripIndent()
    }

    private void writeCompliantPlugin() {
        file('src/main/java/io/kestra/plugin/acme/Reverse.java').text = '''
            package io.kestra.plugin.acme;
            import io.kestra.core.models.tasks.Task;
            import io.kestra.core.models.annotations.Plugin;
            import io.kestra.core.models.annotations.Example;
            import io.kestra.core.models.annotations.PluginProperty;
            import io.swagger.v3.oas.annotations.media.Schema;

            @Schema(title = "Reverse text", description = "Reverses the input string")
            @Plugin(examples = {
                @Example(full = true, code = {
                    "id: demo",
                    "namespace: company.team",
                    "tasks:",
                    "  - id: rev",
                    "    type: io.kestra.plugin.acme.Reverse"
                })
            })
            public class Reverse implements Task {
                @Schema(title = "Text to reverse")
                @PluginProperty(group = "main")
                private String text;
            }
        '''.stripIndent()
        file('src/main/java/io/kestra/plugin/acme/package-info.java').text = '''
            @PluginSubGroup(categories = PluginSubGroup.PluginCategory.DATA)
            package io.kestra.plugin.acme;
            import io.kestra.core.models.annotations.PluginSubGroup;
        '''.stripIndent()
        file('src/main/resources/metadata/index.yaml').text = '''
            group: io.kestra.plugin.acme
            name: acme
            title: Acme
            description: Acme tasks
            body: ""
        '''.stripIndent()
        file('src/main/resources/icons/plugin-icon.svg').text = '<svg/>'
        file('src/main/resources/doc/io.kestra.plugin.acme.md').text = (1..12).collect { "line ${it}" }.join('\n')
    }

    private void writeNonCompliantPlugin() {
        // Task with no @Schema, no @Plugin examples; no metadata/icons/doc/package-info.
        file('src/main/java/io/kestra/plugin/acme/Reverse.java').text = '''
            package io.kestra.plugin.acme;
            import io.kestra.core.models.tasks.Task;
            public class Reverse implements Task {
                private String text;
            }
        '''.stripIndent()
    }

    @Test
    void 'plugin applies and registers lintPluginDocs'() {
        applyPlugin()
        def result = runner('tasks', '--all').build()
        assertTrue(result.output.contains('lintPluginDocs'))
    }

    @Test
    void 'lintPluginDocs is wired into check'() {
        applyPlugin()
        def result = runner('check', '--dry-run').build()
        assertTrue(result.output.contains(':lintPluginDocs'))
    }

    @Test
    void 'compliant plugin passes with zero violations'() {
        applyPlugin()
        writeCompliantPlugin()
        def result = runner('lintPluginDocs').build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':lintPluginDocs').outcome)
        assertTrue(result.output.contains('All') && result.output.contains('rules passed'))
    }

    @Test
    void 'non-compliant plugin fails with rule ids'() {
        applyPlugin()
        writeNonCompliantPlugin()
        def result = runner('lintPluginDocs').buildAndFail()
        assertTrue(result.output.contains('SCHEMA-001'))
        assertTrue(result.output.contains('PLUGIN-001'))
        assertTrue(result.output.contains('META-001'))
        assertTrue(result.output.contains('ICON-001'))
        assertTrue(result.output.contains('PKG-001'))
    }

    @Test
    void 'ignoreFailures reports but does not fail'() {
        applyPlugin('pluginDocLint { ignoreFailures = true }')
        writeNonCompliantPlugin()
        def result = runner('lintPluginDocs').build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':lintPluginDocs').outcome)
        assertTrue(result.output.contains('SCHEMA-001'))
    }

    @Test
    void 'disabledRules skips a rule'() {
        applyPlugin("pluginDocLint { disabledRules = ['SCHEMA-001', 'PLUGIN-001', 'META-001', 'META-002', 'META-003', 'META-004', 'ICON-001', 'PKG-001', 'PKG-003', 'DOC-001', 'PROP-002'] }")
        writeNonCompliantPlugin()
        def result = runner('lintPluginDocs').buildAndFail()
        assertFalse(result.output.contains('SCHEMA-001'))
    }
}
