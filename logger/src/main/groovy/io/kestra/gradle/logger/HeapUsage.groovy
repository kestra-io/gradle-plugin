package io.kestra.gradle.logger

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Captures actual peak JVM heap usage of a Test task's forked worker(s) via unified GC logging,
 * rather than a custom -javaagent: it is a public JVM flag plus the public Test task API, with no
 * new publishable artifact and no reflection into Gradle internals.
 *
 * Only samples heap at GC-pause boundaries, so a module with light heap churn may show no GC lines
 * at all -- callers must treat a -1 percent as "n/a", never as "0% used".
 */
class HeapUsage {

    /**
     * Matches the "before->after(total)" heap-change segment common to G1/Parallel/Serial's
     * default -Xlog:gc lines, e.g. "45M->12M(128M)" or "45056K->12288K(131072K)". Group 1/2 is the
     * heap used just before the collection; group 3/4 is the heap capacity at that same moment.
     */
    private static final Pattern BEFORE_AFTER_TOTAL =
        Pattern.compile(/(\d+(?:\.\d+)?)([BKMGT])->\d+(?:\.\d+)?[BKMGT]\((\d+(?:\.\d+)?)([BKMGT])\)/)

    /** %p is resolved by the JVM itself at worker startup, so concurrent forks never collide. */
    static String jvmLoggingArg(File gcLogDir) {
        return "-Xlog:gc:file=${new File(gcLogDir, 'gc-%p.log').absolutePath}"
    }

    /**
     * Scans every gc-*.log file in dir and returns the used/capacity pair with the highest
     * used/capacity ratio -- i.e. the moment of greatest memory pressure, not simply the largest
     * absolute byte count, since heap capacity itself can vary as G1 resizes it.
     *
     * @return a {@link Sample} with both fields -1 if the directory is missing/empty or no GC ever occurred.
     */
    static Sample peakUsage(File dir) {
        return pickBy(dir) { List<Sample> samplesInFile -> samplesInFile }
    }

    /**
     * Scans every gc-*.log file in dir and returns the most recently logged used/capacity pair --
     * i.e. the current heap pressure as of the last GC pause, not the worst one seen across the whole
     * task run so far. Across
     * concurrent forks, the fork with the highest pressure at its own most recent sample wins, since
     * that is the one closest to actually running out of heap right now.
     *
     * @return a {@link Sample} with both fields -1 if the directory is missing/empty or no GC has occurred yet.
     */
    static Sample latestUsage(File dir) {
        return pickBy(dir) { List<Sample> samplesInFile -> samplesInFile ? [samplesInFile.last()] : [] }
    }

    /**
     * Shared scan: reads every gc-*.log file into an ordered list of before/after/total samples, lets
     * the caller narrow each file's samples down to the ones worth comparing, then returns the
     * highest used/capacity ratio among what's left.
     */
    private static Sample pickBy(File dir, Closure<List<Sample>> narrow) {
        Sample best = new Sample()
        if (dir == null || !dir.isDirectory()) return best

        File[] logs = dir.listFiles { File f -> f.name.startsWith('gc-') && f.name.endsWith('.log') }
        if (logs == null || logs.length == 0) return best

        double bestPercent = -1d
        logs.each { File log ->
            narrow(samplesOf(log)).each { Sample sample ->
                double percent = sample.percent()
                if (percent > bestPercent) {
                    bestPercent = percent
                    best = sample
                }
            }
        }
        return best
    }

    private static List<Sample> samplesOf(File log) {
        List<Sample> samples = []
        log.eachLine { String line ->
            Matcher matcher = BEFORE_AFTER_TOTAL.matcher(line)
            while (matcher.find()) {
                long used = toBytes(matcher.group(1) as double, matcher.group(2))
                long capacity = toBytes(matcher.group(3) as double, matcher.group(4))
                samples << new Sample(usedBytes: used, capacityBytes: capacity)
            }
        }
        return samples
    }

    private static long toBytes(double value, String unit) {
        switch (unit) {
            case 'B': return value as long
            case 'K': return (value * 1024L) as long
            case 'M': return (value * 1024L * 1024L) as long
            case 'G': return (value * 1024L * 1024L * 1024L) as long
            case 'T': return (value * 1024L * 1024L * 1024L * 1024L) as long
            default: return value as long
        }
    }

    /** -1/-1 = "n/a": no GC observed, or heap capture disabled. */
    static class Sample {
        long usedBytes = -1
        long capacityBytes = -1

        double percent() {
            return (usedBytes < 0 || capacityBytes <= 0) ? -1d : usedBytes / (double) capacityBytes
        }
    }
}
