package io.kestra.gradle.rules

/**
 * Shared constants for the rule set: the allowed value sets and the secret-name patterns.
 */
class RuleConstants {
    /** Allowed {@code @PluginProperty(group = ...)} values (PROP-001). */
    static final Set<String> PROPERTY_GROUPS = [
        'main', 'connection', 'source', 'processing',
        'execution', 'destination', 'reliability', 'advanced', 'deprecated'
    ] as Set

    /** Allowed {@code @PluginSubGroup} categories (PKG-002). */
    static final Set<String> CATEGORIES = [
        'AI', 'BUSINESS', 'CLOUD', 'CORE', 'DATA', 'INFRASTRUCTURE'
    ] as Set

    /**
     * Field-name fragments that flag a property as secret (PROP-002, PLUGIN-005). These match
     * fields that hold a secret value. Bare "token" is replaced by concrete credential-token
     * forms so non-secret names like "pageToken"/"nextPageToken" are not flagged. Bare "secret"
     * and "credential" are deliberately excluded: fields like "secretArn", "secretName" or
     * "credentialId" hold a reference (a vault address or id), not the value itself, and masking
     * them in logs would only hide which secret was used. The concrete forms below catch the
     * value-bearing cases.
     */
    static final List<String> SECRET_NAME_PATTERNS = [
        'password', 'passphrase', 'apikey', 'privatekey', 'clientsecret',
        'apitoken', 'accesstoken', 'refreshtoken', 'authtoken', 'bearertoken', 'sessiontoken'
    ]

    static boolean looksLikeSecret(String fieldName) {
        if (!fieldName) {
            return false
        }
        String lower = fieldName.toLowerCase()
        return SECRET_NAME_PATTERNS.any { lower.contains(it) }
    }

    /** Null or whitespace-only. */
    static boolean blank(Object value) {
        return value == null || value.toString().trim().isEmpty()
    }

    static boolean endsWithPeriod(String value) {
        return value != null && value.trim().endsWith('.')
    }

    private RuleConstants() {}
}
