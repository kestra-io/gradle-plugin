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
     * Field-name fragments that flag a property as secret (PROP-002, PLUGIN-005). Based on the
     * issue's list (password, apiKey, apiToken, token, privateKey, secret, credential), with the
     * bare "token" replaced by concrete credential-token forms. Plain substring matching of bare
     * "token" matches non-secret names like "pageToken"/"nextPageToken"; the concrete forms catch
     * apiToken/accessToken/refreshToken without that false positive.
     */
    static final List<String> SECRET_NAME_PATTERNS = [
        'password', 'apikey', 'privatekey', 'secret', 'credential',
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
