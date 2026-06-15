package io.kestra.gradle.rules

/**
 * Shared constants for the rule set: the allowed value sets and the secret-name patterns.
 */
class RuleConstants {
    /** Allowed {@code @PluginProperty(group = ...)} values (PROP-001). */
    static final Set<String> PROPERTY_GROUPS = [
        'main', 'connection', 'source', 'processing',
        'execution', 'destination', 'reliability', 'advanced'
    ] as Set

    /** Allowed {@code @PluginSubGroup} categories (PKG-002). */
    static final Set<String> CATEGORIES = [
        'AI', 'BUSINESS', 'CLOUD', 'CORE', 'DATA', 'INFRASTRUCTURE'
    ] as Set

    /** Field-name fragments that flag a property as secret (PROP-002, PLUGIN-005). */
    static final List<String> SECRET_NAME_PATTERNS = [
        'password', 'apikey', 'apitoken', 'token', 'privatekey', 'secret', 'credential'
    ]

    static boolean looksLikeSecret(String fieldName) {
        if (!fieldName) {
            return false
        }
        String lower = fieldName.toLowerCase()
        return SECRET_NAME_PATTERNS.any { lower.contains(it) }
    }

    private RuleConstants() {}
}
