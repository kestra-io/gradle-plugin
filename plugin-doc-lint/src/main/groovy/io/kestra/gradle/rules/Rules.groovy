package io.kestra.gradle.rules

/**
 * Registry of every lint rule, in report order.
 */
class Rules {

    static List<Rule> all() {
        return MetadataRules.rules() +
            IconRules.rules() +
            SchemaRules.rules() +
            PluginRules.rules() +
            PropertyRules.rules() +
            PackageRules.rules() +
            DocRules.rules()
    }

    static Rule byId(String id) {
        return all().find { it.id() == id }
    }

    private Rules() {}
}
