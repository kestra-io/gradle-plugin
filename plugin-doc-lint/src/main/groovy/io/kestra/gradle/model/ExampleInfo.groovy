package io.kestra.gradle.model

/**
 * Extracted view of a single {@code @Example} declared inside {@code @Plugin(examples = ...)}.
 * {@code code} is the example YAML with the {@code String[]} joined by newlines.
 */
class ExampleInfo {
    String title
    boolean full
    String lang
    String code
}
