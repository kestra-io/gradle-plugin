package io.kestra.gradle.rules

import org.junit.jupiter.api.Test

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class PackageRulesTest {

    @Test
    void 'PKG-001 flags missing package-info and missing subgroup'() {
        def c = task('io.kestra.plugin.acme.Run')

        def noInfo = model([c], [packages: ['io.kestra.plugin.acme': packageInfo('io.kestra.plugin.acme')]])
        assertEquals(1, run('PKG-001', noInfo).size())

        def noSubGroup = model([c], [packages: ['io.kestra.plugin.acme': packageInfo('io.kestra.plugin.acme', [hasPackageInfo: true])]])
        def v = run('PKG-001', noSubGroup)
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('@PluginSubGroup'))
    }

    @Test
    void 'PKG-001 passes with package-info and subgroup'() {
        def c = task('io.kestra.plugin.acme.Run')
        def m = model([c], [packages: ['io.kestra.plugin.acme': packageInfo('io.kestra.plugin.acme', [hasPackageInfo: true, hasSubGroup: true, subGroupCategories: ['DATA']])]])
        assertTrue(run('PKG-001', m).isEmpty())
    }

    @Test
    void 'PKG-002 flags invalid category'() {
        def c = task('io.kestra.plugin.acme.Run')
        def m = model([c], [packages: ['io.kestra.plugin.acme': packageInfo('io.kestra.plugin.acme', [hasPackageInfo: true, hasSubGroup: true, subGroupCategories: ['TOOL']])]])
        def v = run('PKG-002', m)
        assertEquals(1, v.size())
        assertTrue(v[0].message.contains('TOOL'))
    }

    @Test
    void 'PKG-003 flags mixed root and subpackage placement'() {
        def m = model([
            task('io.kestra.plugin.acme.Root'),
            task('io.kestra.plugin.acme.sub.Child')
        ])
        assertEquals(1, run('PKG-003', m).size())
    }

    @Test
    void 'PKG-003 passes when all tasks are in subpackages'() {
        def m = model([
            task('io.kestra.plugin.acme.a.One'),
            task('io.kestra.plugin.acme.b.Two')
        ])
        assertTrue(run('PKG-003', m).isEmpty())
    }
}
