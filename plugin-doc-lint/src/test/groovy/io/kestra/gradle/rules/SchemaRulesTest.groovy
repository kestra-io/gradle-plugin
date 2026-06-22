package io.kestra.gradle.rules

import io.kestra.gradle.model.ClassInfo
import org.junit.jupiter.api.Test

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class SchemaRulesTest {

    @Test
    void 'SCHEMA-001 flags task without schema'() {
        def c = task('io.kestra.plugin.acme.Run')
        assertEquals(1, run('SCHEMA-001', model([c])).size())
    }

    @Test
    void 'SCHEMA-001 passes with schema'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.hasSchema = true
        assertTrue(run('SCHEMA-001', model([c])).isEmpty())
    }

    @Test
    void 'SCHEMA-002 flags empty description'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.hasSchema = true
        c.schemaTitle = 'Run'
        c.schemaDescription = ''
        assertEquals(1, run('SCHEMA-002', model([c])).size())
    }

    @Test
    void 'SCHEMA-003 flags undocumented field but ignores static and transient'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [
            field('format'),
            field('CONST', [isStatic: true]),
            field('cache', [isTransient: true]),
            field('documented', [hasSchema: true])
        ]
        def v = run('SCHEMA-003', model([c]))
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('#format'))
    }

    @Test
    void 'SCHEMA-001 covers task runners and log exporters'() {
        def runner = runner('io.kestra.plugin.acme.runner.MyRunner')
        def exporter = logExporter('io.kestra.plugin.acme.logs.MyExporter')
        def v = run('SCHEMA-001', model([runner, exporter]))
        assertEquals(2, v.size())
    }

    @Test
    void 'SCHEMA-003 ignores non-property internal fields'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [
            field('format'),
            field('isActive', [isProperty: false])
        ]
        def v = run('SCHEMA-003', model([c]))
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('#format'))
    }

    @Test
    void 'SCHEMA-004 flags undocumented output field'() {
        def out = output('io.kestra.plugin.acme.Run$Output')
        out.fields = [field('uri')]
        assertEquals(1, run('SCHEMA-004', model([out])).size())
    }

    @Test
    void 'SCHEMA-005 flags titles ending with period on class and field'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.schemaTitle = 'Run a job.'
        c.fields = [field('format', [hasSchema: true, schemaTitle: 'The format.'])]
        def out = output('io.kestra.plugin.acme.Run$Output')
        out.fields = [field('uri', [hasSchema: true, schemaTitle: 'Output URI'])]
        def v = run('SCHEMA-005', model([c, out]))
        assertEquals(2, v.size())
    }

    @Test
    void 'SCHEMA-005 ignores a period in a title inherited from a framework type'() {
        // e.g. a field implementing a core interface whose getter @Schema title ends with a period;
        // the plugin cannot edit core, so the title must not be flagged.
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [field('outputFiles', [hasSchema: true, schemaTitle: 'Files to send to internal storage.', schemaFromOwnCode: false])]
        assertTrue(run('SCHEMA-005', model([c])).isEmpty())
    }

    @Test
    void 'SCHEMA-005 still flags an own field title ending with a period'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [field('format', [hasSchema: true, schemaTitle: 'The format.', schemaFromOwnCode: true])]
        assertEquals(1, run('SCHEMA-005', model([c])).size())
    }
}
