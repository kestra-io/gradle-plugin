package io.kestra.gradle.logger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class HeapUsageTest {

    @TempDir
    Path dir

    private File writeLog(String name, String content) {
        File f = dir.resolve(name).toFile()
        f.text = content
        return f
    }

    @Test
    void 'peak usage picks the highest used-over-capacity ratio, not the largest absolute byte count'() {
        // Large absolute usage but low pressure (20%): must lose to the smaller-but-fuller sample below.
        writeLog('gc-11111.log', '''
            [0.001s][info][gc] Using G1
            [0.512s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 500M->200M(1000M) 3.456ms
        '''.stripIndent())
        // Smaller absolute usage but high pressure (80%): this is the real peak.
        writeLog('gc-22222.log', '''
            [2.001s][info][gc] GC(1) Pause Young (Allocation Failure) 90M->80M(100M) 5.001ms
        '''.stripIndent())

        HeapUsage.Sample peak = HeapUsage.peakUsage(dir.toFile())

        assertEquals(90L * 1024 * 1024, peak.usedBytes)
        assertEquals(100L * 1024 * 1024, peak.capacityBytes)
        assertEquals(0.9d, peak.percent(), 0.0001d)
    }

    @Test
    void 'latest usage picks the most recently logged sample per file, not the highest overall'() {
        // Highest pressure (80%) happened first and is stale; the most recent sample (25%) must win.
        writeLog('gc-11111.log', '''
            [0.512s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 80M->80M(100M) 3.456ms
            [2.001s][info][gc] GC(1) Pause Young (Allocation Failure) 25M->25M(100M) 5.001ms
        '''.stripIndent())

        HeapUsage.Sample latest = HeapUsage.latestUsage(dir.toFile())

        assertEquals(25L * 1024 * 1024, latest.usedBytes)
        assertEquals(100L * 1024 * 1024, latest.capacityBytes)
        assertEquals(0.25d, latest.percent(), 0.0001d)
    }

    @Test
    void 'latest usage compares each forks own most recent sample, favoring the busier fork'() {
        writeLog('gc-11111.log', '''
            [2.001s][info][gc] GC(1) Pause Young (Allocation Failure) 40M->35M(100M) 5.001ms
        '''.stripIndent())
        writeLog('gc-22222.log', '''
            [2.001s][info][gc] GC(1) Pause Young (Allocation Failure) 80M->75M(100M) 5.001ms
        '''.stripIndent())

        HeapUsage.Sample latest = HeapUsage.latestUsage(dir.toFile())

        assertEquals(80L * 1024 * 1024, latest.usedBytes)
        assertEquals(0.8d, latest.percent(), 0.0001d)
    }

    @Test
    void 'returns n-slash-a latest for an empty directory'() {
        HeapUsage.Sample latest = HeapUsage.latestUsage(dir.toFile())

        assertEquals(-1L, latest.usedBytes)
        assertEquals(-1L, latest.capacityBytes)
        assertTrue(latest.percent() < 0)
    }

    @Test
    void 'returns n-slash-a peak for an empty directory'() {
        HeapUsage.Sample peak = HeapUsage.peakUsage(dir.toFile())

        assertEquals(-1L, peak.usedBytes)
        assertEquals(-1L, peak.capacityBytes)
        assertTrue(peak.percent() < 0)
    }

    @Test
    void 'returns n-slash-a peak when no line matches -- no GC occurred'() {
        writeLog('gc-11111.log', '''
            [0.001s][info][gc] Using G1
            [0.002s][info][gc,init] Heap Region Size: 1M
        '''.stripIndent())

        HeapUsage.Sample peak = HeapUsage.peakUsage(dir.toFile())

        assertTrue(peak.percent() < 0)
    }

    @Test
    void 'returns n-slash-a peak for a missing directory'() {
        HeapUsage.Sample peak = HeapUsage.peakUsage(new File(dir.toFile(), 'does-not-exist'))

        assertTrue(peak.percent() < 0)
    }

    @Test
    void 'jvmLoggingArg embeds the pid token so concurrent forks never collide'() {
        String arg = HeapUsage.jvmLoggingArg(dir.toFile())

        assert arg.startsWith('-Xlog:gc:file=')
        assert arg.endsWith('gc-%p.log')
    }
}
