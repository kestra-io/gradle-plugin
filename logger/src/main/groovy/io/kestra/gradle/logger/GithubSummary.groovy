package io.kestra.gradle.logger

/**
 * Writes a per-module test result table to $GITHUB_STEP_SUMMARY. Only meaningful inside a GitHub
 * Actions job; callers are expected to check {@link #summaryFile()} for null before calling
 * {@link #write}.
 */
class GithubSummary {

    static File summaryFile() {
        String path = System.getenv('GITHUB_STEP_SUMMARY')
        return path ? new File(path) : null
    }

    static void write(List<ModuleStats> perModule, List<String> failureLabels, List<SlowEntry> slowest, int slowThreshold) {
        File file = summaryFile()
        if (file == null) return

        StringBuilder sb = new StringBuilder()
        sb << '## Test summary\n\n'
        sb << '| module | total | passed | failed | skipped | duration |\n'
        sb << '|---|---:|---:|---:|---:|---:|\n'

        int total = 0, passed = 0, failed = 0, skipped = 0
        long duration = 0
        perModule.each { ModuleStats m ->
            sb << "| ${m.moduleName} | ${m.total} | ${m.passed} | ${m.failed} | ${m.skipped} | ${Durations.format(m.durationMillis)} |\n"
            total += m.total; passed += m.passed; failed += m.failed; skipped += m.skipped; duration += m.durationMillis
        }
        sb << "| **TOTAL** | **${total}** | **${passed}** | **${failed}** | **${skipped}** | **${Durations.format(duration)}** |\n"

        if (failureLabels) {
            sb << '\n### Failures\n\n'
            failureLabels.each { sb << "- ${it}\n" }
        }

        if (slowest) {
            sb << "\n### Slowest (>= ${slowThreshold}ms)\n\n"
            slowest.each { SlowEntry e -> sb << "- ${e.label} — ${Durations.format(e.durationMillis)}\n" }
        }

        file << sb.toString()
    }

    static class ModuleStats {
        String moduleName
        int total, passed, failed, skipped
        long durationMillis
        long peakHeapUsedBytes = -1 // -1 = "n/a": no GC observed, or heap capture disabled
        long peakHeapCapacityBytes = -1
    }

    static class SlowEntry {
        String label
        long durationMillis
    }
}
