package io.kestra.gradle.rules

import org.junit.jupiter.api.Test

import static io.kestra.gradle.Fixtures.*
import static org.junit.jupiter.api.Assertions.*

class PropertyRulesTest {

    @Test
    void 'PROP-001 flags bad group and passes a valid one'() {
        def bad = task('io.kestra.plugin.acme.Bad')
        bad.fields = [field('host', [hasPluginProperty: true, pluginPropertyGroup: 'misc'])]
        assertEquals(1, run('PROP-001', model([bad])).size())

        def ok = task('io.kestra.plugin.acme.Ok')
        ok.fields = [field('host', [hasPluginProperty: true, pluginPropertyGroup: 'connection'])]
        assertTrue(run('PROP-001', model([ok])).isEmpty())
    }

    @Test
    void 'PROP-002 flags unmarked secret field'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [field('apiToken', [hasPluginProperty: true, pluginPropertyGroup: 'connection'])]
        def v = run('PROP-002', model([c]))
        assertEquals(1, v.size())
        assertTrue(v[0].location.endsWith('#apiToken'))
    }

    @Test
    void 'PROP-002 ignores secret-reference fields'() {
        // A field that names/points to a secret (arn, id) is not the secret value itself.
        ['secretArn', 'secretName', 'credentialId', 'pageToken'].each { name ->
            def c = task('io.kestra.plugin.acme.Run')
            c.fields = [field(name, [hasPluginProperty: true, pluginPropertyGroup: 'connection'])]
            assertTrue(run('PROP-002', model([c])).isEmpty(), "${name} should not be flagged")
        }
    }

    @Test
    void 'PROP-002 passes when secret marked'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [field('apiToken', [hasPluginProperty: true, pluginPropertyGroup: 'connection', pluginPropertySecret: true])]
        assertTrue(run('PROP-002', model([c])).isEmpty())
    }

    @Test
    void 'PROP-003 flags version field'() {
        def c = task('io.kestra.plugin.acme.Run')
        c.fields = [field('version')]
        assertEquals(1, run('PROP-003', model([c])).size())
    }
}
