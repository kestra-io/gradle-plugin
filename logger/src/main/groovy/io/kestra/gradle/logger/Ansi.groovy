package io.kestra.gradle.logger

/**
 * Minimal ANSI color support. Colors are auto-disabled when the {@code NO_COLOR} environment
 * variable is set (https://no-color.org), regardless of what the caller asks for — CI log viewers
 * that don't render ANSI turn every line into escape-code noise otherwise.
 */
class Ansi {

    private static final String ESC = (char) 0x1B

    static final String RESET = "${ESC}[0m"
    static final String BOLD = "${ESC}[1m"
    // A fixed 256-color gray, not SGR 90 ("bright black") -- many dark terminal themes (Dracula,
    // Nord, One Dark, Solarized, GitHub Dark, ...) remap color 8/90 to a blue-tinted gray, which
    // made "gray" text look blue. 256-color codes bypass the theme's 16-color remapping.
    static final String GRAY = "${ESC}[38;5;245m"
    static final String RED = "${ESC}[31m"
    static final String GREEN = "${ESC}[32m"
    static final String YELLOW = "${ESC}[33m"
    static final String CYAN = "${ESC}[36m"

    /** 256-color codes used as a stable per-module palette. Chosen to be readable on dark terminals. */
    static final List<String> MODULE_PALETTE = [
        "${ESC}[38;5;110m", "${ESC}[38;5;114m", "${ESC}[38;5;180m", "${ESC}[38;5;175m",
        "${ESC}[38;5;150m", "${ESC}[38;5;222m", "${ESC}[38;5;117m", "${ESC}[38;5;186m",
        "${ESC}[38;5;173m", "${ESC}[38;5;108m", "${ESC}[38;5;146m", "${ESC}[38;5;215m",
    ]

    private static Boolean noColorOverride = null

    /** Test-only hook; production code should never call this. */
    static void setNoColorOverrideForTesting(Boolean value) {
        noColorOverride = value
    }

    static boolean colorsEnabled(boolean requested) {
        if (!requested) return false
        if (noColorOverride != null) return !noColorOverride
        return System.getenv('NO_COLOR') == null
    }

    static String wrap(String text, String code, boolean enabled) {
        if (!enabled || code == null) return text
        return code + text + RESET
    }

    static String colorForModule(String moduleName, boolean enabled) {
        if (!enabled) return null
        int index = Math.abs(moduleName.hashCode()) % MODULE_PALETTE.size()
        return MODULE_PALETTE[index]
    }
}
