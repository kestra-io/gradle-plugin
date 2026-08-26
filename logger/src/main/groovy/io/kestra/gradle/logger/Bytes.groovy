package io.kestra.gradle.logger

/** Formats byte counts for the console summary. -1 (the "no GC data" sentinel) always renders as "n/a". */
class Bytes {

    private static final String[] UNITS = ['B', 'KB', 'MB', 'GB', 'TB']

    static String format(long bytes) {
        if (bytes < 0) return 'n/a'
        if (bytes < 1024) return "${bytes} B"

        double value = bytes
        int unit = 0
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024
            unit++
        }
        return String.format('%.1f %s', value, UNITS[unit])
    }
}
