package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class MetadataRulesTest {

    @TempDir
    Path tmp

    private File metadataDir() {
        File dir = new File(tmp.toFile(), 'metadata')
        dir.mkdirs()
        return dir
    }

    private void write(String name, String content) {
        new File(metadataDir(), name).text = content
    }

    private PluginModel modelFor(List classes) {
        return model(classes, [resourceRoot: tmp.toFile()])
    }

    @Test
    void 'META-001 fails when index missing'() {
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertEquals(1, run('META-001', m).size())
    }

    @Test
    void 'META-001 passes when index present'() {
        write('index.yaml', 'group: io.kestra.plugin.acme')
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertTrue(run('META-001', m).isEmpty())
    }

    @Test
    void 'META-002 fails for subpackage without metadata'() {
        def m = modelFor([
            task('io.kestra.plugin.acme.Root'),
            task('io.kestra.plugin.acme.sub.Child')
        ])
        def v = run('META-002', m)
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('sub.yaml'))
    }

    @Test
    void 'META-003 flags empty required field but allows empty body'() {
        write('index.yaml', '''
group: io.kestra.plugin.acme
name: acme
title: ""
description: does things
body: ""
'''.stripIndent())
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        def v = run('META-003', m)
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('title'))
    }

    @Test
    void 'META-003 passes with all required fields'() {
        write('index.yaml', '''
group: io.kestra.plugin.acme
name: acme
title: Acme
description: does things
body: ""
'''.stripIndent())
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertTrue(run('META-003', m).isEmpty())
    }

    @Test
    void 'META-004 flags group mismatch'() {
        write('index.yaml', 'group: io.kestra.plugin.WRONG\nname: a\ntitle: A\ndescription: d\nbody: ""')
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        def v = run('META-004', m)
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('io.kestra.plugin.acme'))
    }
}
