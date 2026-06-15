package io.kestra.gradle

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.ExampleInfo
import io.kestra.gradle.model.FieldInfo
import io.kestra.gradle.model.PackageInfo
import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation
import io.kestra.gradle.rules.Rule
import io.kestra.gradle.rules.Rules

/**
 * Builders for hand-made {@link PluginModel} graphs so rules can be tested without compiling
 * real Kestra classes.
 */
class Fixtures {

    static ClassInfo clazz(String fqcn, ClassInfo.Kind kind) {
        ClassInfo c = new ClassInfo()
        c.fqcn = fqcn
        c.packageName = fqcn.contains('.') ? fqcn.substring(0, fqcn.lastIndexOf('.')) : ''
        c.simpleName = fqcn.contains('.') ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn
        c.kind = kind
        return c
    }

    static ClassInfo task(String fqcn) {
        return clazz(fqcn, ClassInfo.Kind.TASK)
    }

    static ClassInfo trigger(String fqcn) {
        return clazz(fqcn, ClassInfo.Kind.TRIGGER)
    }

    static ClassInfo output(String fqcn) {
        return clazz(fqcn, ClassInfo.Kind.OUTPUT)
    }

    static FieldInfo field(String name, Map opts = [:]) {
        FieldInfo f = new FieldInfo()
        f.name = name
        f.isStatic = opts.get('isStatic', false)
        f.isTransient = opts.get('isTransient', false)
        f.hasSchema = opts.get('hasSchema', false)
        f.schemaTitle = opts.get('schemaTitle')
        f.schemaDescription = opts.get('schemaDescription')
        f.hasPluginProperty = opts.get('hasPluginProperty', false)
        f.pluginPropertyGroup = opts.get('pluginPropertyGroup')
        f.pluginPropertySecret = opts.get('pluginPropertySecret', false)
        return f
    }

    static ExampleInfo example(Map opts = [:]) {
        ExampleInfo e = new ExampleInfo()
        e.title = opts.get('title')
        e.full = opts.get('full', false)
        e.lang = opts.get('lang')
        e.code = opts.get('code', '')
        return e
    }

    static PackageInfo packageInfo(String name, Map opts = [:]) {
        PackageInfo p = new PackageInfo(name: name)
        p.hasPackageInfo = opts.get('hasPackageInfo', false)
        p.hasSubGroup = opts.get('hasSubGroup', false)
        p.subGroupCategories = opts.get('subGroupCategories', []) as List
        return p
    }

    static PluginModel model(List<ClassInfo> classes, Map opts = [:]) {
        PluginModel m = new PluginModel()
        m.classes = classes
        m.packages = opts.get('packages', [:])
        m.resourceRoot = opts.get('resourceRoot')
        m.sourceRoot = opts.get('sourceRoot')
        return m
    }

    static List<Violation> run(String ruleId, PluginModel model) {
        Rule rule = Rules.byId(ruleId)
        assert rule != null: "unknown rule ${ruleId}"
        return rule.check(model)
    }
}
