package io.kestra.gradle.model

/**
 * A single documentation-lint failure: the rule that flagged it, where it occurred
 * (class FQCN or file path), and a message that includes a one-line remediation hint.
 */
class Violation {
    final String ruleId
    final String location
    final String message

    Violation(String ruleId, String location, String message) {
        this.ruleId = ruleId
        this.location = location
        this.message = message
    }

    @Override
    String toString() {
        return "[${ruleId}] ${location} - ${message}"
    }
}
