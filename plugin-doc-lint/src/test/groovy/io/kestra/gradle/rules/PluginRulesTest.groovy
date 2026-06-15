package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class PluginRulesTest {

    @TempDir
    Path tmp

    private static final String FULL_EXAMPLE = '''
id: my_flow
namespace: company.team
tasks:
  - id: run
    type: io.kestra.plugin.acme.Run
'''.stripIndent()

    @Test
    void 'PLUGIN-001 flags missing examples'() {
        def c = task('io.kestra.plugin.acme.Run')
        assertEquals(1, run('PLUGIN-001', model([c])).size())
    }

    @Test
    void 'PLUGIN-002 flags non-full example'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: false, code: FULL_EXAMPLE])]
        assertEquals(1, run('PLUGIN-002', model([c])).size())
    }

    @Test
    void 'PLUGIN-003 flags example missing id namespace tasks'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: 'format: "x"'])]
        def v = run('PLUGIN-003', model([c]))
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('id'))
    }

    @Test
    void 'PLUGIN-003 passes for full flow'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: FULL_EXAMPLE])]
        assertTrue(run('PLUGIN-003', model([c])).isEmpty())
    }

    @Test
    void 'PLUGIN-004 flags explicit lang yaml in source'() {
        File src = tmp.toFile()
        File javaFile = new File(src, 'io/kestra/plugin/acme/Run.java')
        javaFile.parentFile.mkdirs()
        javaFile.text = '@Example(full = true, lang = "yaml", code = {"id: x"})'
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: FULL_EXAMPLE])]
        def m = model([c], [sourceRoot: src])
        assertEquals(1, run('PLUGIN-004', m).size())
    }

    @Test
    void 'PLUGIN-005 flags plain-text secret in example'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: '''
id: f
namespace: company.team
tasks:
  - id: run
    type: io.kestra.plugin.acme.Run
    password: hunter2
'''.stripIndent()])]
        def v = run('PLUGIN-005', model([c]))
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('password'))
    }

    @Test
    void 'PLUGIN-005 allows a value templated from an input'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: '''
id: f
namespace: company.team
tasks:
  - id: run
    type: io.kestra.plugin.acme.Run
    password: "{{ inputs.db_password }}"
'''.stripIndent()])]
        assertTrue(run('PLUGIN-005', model([c])).isEmpty())
    }

    @Test
    void 'PLUGIN-005 allows templated secret'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.examples = [example([full: true, code: '''
id: f
namespace: company.team
tasks:
  - id: run
    type: io.kestra.plugin.acme.Run
    password: "{{ secret('DB_PASSWORD') }}"
'''.stripIndent()])]
        assertTrue(run('PLUGIN-005', model([c])).isEmpty())
    }
}
