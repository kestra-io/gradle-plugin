package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class DocRulesTest {

    @TempDir
    Path tmp

    private File dir(String name) {
        File d = new File(tmp.toFile(), name)
        d.mkdirs()
        return d
    }

    private PluginModel modelFor(List classes) {
        return model(classes, [resourceRoot: tmp.toFile()])
    }

    @Test
    void 'DOC-001 flags missing root how-to'() {
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        def v = run('DOC-001', m)
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('io.kestra.plugin.acme.md'))
    }

    @Test
    void 'DOC-001 passes when root how-to exists'() {
        new File(dir('doc'), 'io.kestra.plugin.acme.md').text = 'docs'
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertTrue(run('DOC-001', m).isEmpty())
    }

    @Test
    void 'DOC-002 flags markdown referenced in metadata but absent'() {
        new File(dir('metadata'), 'index.yaml').text = 'group: io.kestra.plugin.acme\nbody: "see guide.md"'
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        def v = run('DOC-002', m)
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('guide.md'))
    }

    @Test
    void 'DOC-003 flags short how-to'() {
        new File(dir('doc'), 'io.kestra.plugin.acme.md').text = 'one line'
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertEquals(1, run('DOC-003', m).size())
    }

    @Test
    void 'DOC-003 passes for long enough how-to'() {
        new File(dir('doc'), 'io.kestra.plugin.acme.md').text = (1..12).collect { "line ${it}" }.join('\n')
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertTrue(run('DOC-003', m).isEmpty())
    }
}
