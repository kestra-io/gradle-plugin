package io.kestra.gradle.logger

/** Formats millisecond durations and picks the testlogger-style slow-threshold color band. */
class Durations {

    /** {@code d >= slowThreshold} -> red, {@code d >= slowThreshold / 2} -> yellow, else gray. */
    static String colorFor(long millis, int slowThreshold) {
        if (slowThreshold <= 0) return Ansi.GRAY
        if (millis >= slowThreshold) return Ansi.RED
        if (millis >= slowThreshold / 2) return Ansi.YELLOW
        return Ansi.GRAY
    }

    static String format(long millis) {
        if (millis < 1000) {
            return "${millis}ms"
        }
        if (millis < 60_000) {
            return String.format('%.1fs', millis / 1000.0d)
        }
        long totalSeconds = millis / 1000
        long minutes = totalSeconds / 60
        long seconds = totalSeconds % 60
        return "${minutes}m ${String.format('%02d', seconds)}s"
    }

    static String formatColored(long millis, int slowThreshold, boolean colorsEnabled) {
        return Ansi.wrap(format(millis), colorFor(millis, slowThreshold), colorsEnabled)
    }
}
