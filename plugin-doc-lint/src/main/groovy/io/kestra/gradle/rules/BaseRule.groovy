package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * Closure-backed {@link Rule}, so each rule group can declare its rules as small lambdas
 * while keeping a unique, filterable id per rule.
 */
class BaseRule implements Rule {
    private final String id
    private final Closure<List<Violation>> body

    BaseRule(String id, Closure<List<Violation>> body) {
        this.id = id
        this.body = body
    }

    @Override
    String id() {
        return id
    }

    @Override
    List<Violation> check(PluginModel model) {
        List<Violation> result = body.call(model)
        return result ?: []
    }
}
