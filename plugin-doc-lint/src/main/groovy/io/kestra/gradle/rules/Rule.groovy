package io.kestra.gradle.rules

import io.kestra.gradle.model.PluginModel
import io.kestra.gradle.model.Violation

/**
 * A single lint rule. Pure function over the model so it can be unit-tested in isolation.
 */
interface Rule {
    String id()

    List<Violation> check(PluginModel model)
}
