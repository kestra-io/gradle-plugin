package io.kestra.gradle.logger

/**
 * Builds the colored "[module:task]" prefix every line this plugin prints starts with. Not padded
 * or truncated — the prefix is exactly as wide as "module" or "module:task" is.
 */
class Prefix {

    /**
     * @param moduleName project path with no leading colon, e.g. "core" or "plugin-notifications"
     * @param label      task name, test coordinates, or null for a module-only prefix
     */
    static String build(String moduleName, String label, boolean colorsEnabled) {
        String raw = label ? "${moduleName}:${label}" : moduleName
        String color = Ansi.colorForModule(moduleName, colorsEnabled)
        return "[${Ansi.wrap(raw, color, colorsEnabled)}] "
    }
}
