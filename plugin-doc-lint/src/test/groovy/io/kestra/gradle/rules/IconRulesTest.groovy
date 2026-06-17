package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class IconRulesTest {

    @TempDir
    Path tmp

    private File iconsDir() {
        File dir = new File(tmp.toFile(), 'icons')
        dir.mkdirs()
        return dir
    }

    private PluginModel modelFor(List classes) {
        return model(classes, [resourceRoot: tmp.toFile()])
    }

    @Test
    void 'ICON-001 fails without plugin icon'() {
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertEquals(1, run('ICON-001', m).size())
    }

    @Test
    void 'ICON-001 passes with plugin icon'() {
        new File(iconsDir(), 'plugin-icon.svg').text = '<svg/>'
        def m = modelFor([task('io.kestra.plugin.acme.Run')])
        assertTrue(run('ICON-001', m).isEmpty())
    }

    @Test
    void 'ICON-002 requires fully qualified subpackage icon'() {
        new File(iconsDir(), 'plugin-icon.svg').text = '<svg/>'
        def m = modelFor([
            task('io.kestra.plugin.acme.Root'),
            task('io.kestra.plugin.acme.sub.Child')
        ])
        def v = run('ICON-002', m)
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('io.kestra.plugin.acme.sub.svg'))

        new File(iconsDir(), 'io.kestra.plugin.acme.sub.svg').text = '<svg/>'
        assertTrue(run('ICON-002', m).isEmpty())
    }
}
