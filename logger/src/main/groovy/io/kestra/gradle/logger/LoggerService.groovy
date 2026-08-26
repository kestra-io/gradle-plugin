package io.kestra.gradle.logger

import io.kestra.gradle.KestraLoggerExtension
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestResult
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BuildService that owns every line this plugin prints. Centralising rendering here — rather than
 * printing from each Test task's own listener — is what keeps a failing test's multi-line block
 * atomic under {@code --parallel}: every print goes through {@link #printLines}, which holds a lock
 * for the whole block, so two modules' output can never interleave mid-block.
 *
 * Registered once per build via {@code sharedServices.registerIfAbsent}, shared by every project.
 */
abstract class LoggerService implements BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {

    @Override
    BuildServiceParameters.None getParameters() { return null }

    protected final Object printLock = new Object()
    protected final PrintStream out = System.out

    // Plain config values, copied out of the extension once it is fully configured (see configure()).
    // Never hold a live extension/Project reference here: BuildService instances can outlive
    // configuration and must stay configuration-cache friendly.
    protected volatile boolean taskLineEnabled = true
    protected volatile boolean taskColors = true
    protected volatile Set<String> taskSkipOutcomes = ['NO-SOURCE'] as Set
    protected volatile boolean showCompileErrors = true

    protected volatile int slowThreshold = 2000
    protected volatile boolean showExceptions = true
    protected volatile boolean showFullStackTraces = true
    protected volatile boolean showCauses = true
    protected volatile boolean showPassedStandardStreams = false
    protected volatile boolean showSkippedStandardStreams = true
    protected volatile boolean showFailedStandardStreams = true
    protected volatile List<String> stackTraceFilters = []
    protected volatile boolean fullNamespace = false
    protected volatile boolean showStart = false

    protected volatile boolean heartbeatEnabled = true
    protected volatile long heartbeatThreshold = 5_000
    protected volatile long heartbeatInterval = 5_000
    protected volatile boolean heartbeatDisplayOutput = true

    protected volatile boolean heapEnabled = true
    protected volatile long heapIntervalMillis = 120_000

    protected volatile boolean githubAnnotations = true
    protected volatile boolean githubJobSummary = true

    protected final Map<Object, RunningTest> runningTests = new ConcurrentHashMap<>()
    protected final Map<String, GithubSummary.ModuleStats> perModuleStats = new ConcurrentHashMap<>()
    // taskPath -> gc log dir, registered from the Test task's own doFirst (so a task that never ran
    // this build -- UP-TO-DATE, FROM-CACHE, skipped -- simply never has an entry here).
    protected final Map<String, File> heapLogDirs = new ConcurrentHashMap<>()
    // taskPath -> millis of the last "current heap" line printed for it, so tickHeap() can debounce
    // independently of the heartbeat tick it rides on. No entry yet == never announced.
    protected final Map<String, Long> heapLastAnnounceMillis = new ConcurrentHashMap<>()
    protected final List<String> failureLabels = Collections.synchronizedList(new ArrayList<>())
    protected final List<GithubSummary.SlowEntry> slowEntries = Collections.synchronizedList(new ArrayList<>())
    protected final AtomicInteger testIdSeq = new AtomicInteger()

    protected ScheduledExecutorService tickExecutor

    /** Called once from the plugin after the extension is fully configured (settingsEvaluated). */
    synchronized void configure(KestraLoggerExtension ext, String rootProjectName) {
        this.rootProjectName = rootProjectName
        taskLineEnabled = ext.enabled && ext.task.enabled
        taskColors = ext.task.colors
        taskSkipOutcomes = new HashSet<>(ext.task.skipOutcomes)
        showCompileErrors = ext.task.showCompileErrors

        slowThreshold = ext.test.slowThreshold
        showExceptions = ext.test.showExceptions
        showFullStackTraces = ext.test.showFullStackTraces
        showCauses = ext.test.showCauses
        showPassedStandardStreams = ext.test.showPassedStandardStreams
        showSkippedStandardStreams = ext.test.showSkippedStandardStreams
        showFailedStandardStreams = ext.test.showFailedStandardStreams
        stackTraceFilters = new ArrayList<>(ext.test.stackTraceFilters)
        fullNamespace = ext.test.fullNamespace
        showStart = ext.test.showStart

        heartbeatEnabled = ext.enabled && ext.test.heartbeat.enabled
        heartbeatThreshold = ext.test.heartbeat.threshold
        heartbeatInterval = ext.test.heartbeat.interval
        heartbeatDisplayOutput = ext.test.heartbeat.displayOutput

        heapEnabled = ext.enabled && ext.test.heap.enabled
        heapIntervalMillis = ext.test.heap.interval

        githubAnnotations = ext.github.annotations
        githubJobSummary = ext.github.jobSummary

        boolean heapTickingNeeded = heapEnabled && heapIntervalMillis > 0
        if ((heartbeatEnabled || heapTickingNeeded) && tickExecutor == null) {
            tickExecutor = Executors.newSingleThreadScheduledExecutor { Runnable r ->
                Thread t = new Thread(r, 'kestra-logger-tick')
                t.daemon = true // must never block JVM/daemon shutdown
                return t
            }
            tickExecutor.scheduleAtFixedRate({ tick() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    protected boolean colorsEnabled(boolean requested) {
        return Ansi.colorsEnabled(requested)
    }

    // ------------------------------------------------------------------------------------------
    // Task start/finish lines (every task, via BuildEventsListenerRegistry -- public API).
    // ------------------------------------------------------------------------------------------

    @Override
    void onFinish(FinishEvent event) {
        if (!(event instanceof TaskFinishEvent)) return
        TaskFinishEvent tfe = (TaskFinishEvent) event
        String taskPath = tfe.descriptor.taskPath
        String outcome = outcomeOf(tfe.result)

        // Runs from this same completion hook, not from doLast: doLast is skipped once any action in
        // a task throws, which silently dropped heap capture for every Test task with a failing test.
        // onTaskCompletion fires unconditionally -- success, failure, or skip -- so this always runs.
        processHeapUsage(taskPath)

        if (!taskLineEnabled) return
        if (taskSkipOutcomes.contains(outcome)) return

        long duration = Math.max(0L, tfe.result.endTime - tfe.result.startTime)
        String moduleName = moduleNameOf(taskPath)
        String taskName = taskNameOf(taskPath)
        boolean colors = colorsEnabled(taskColors)
        String prefix = Prefix.build(moduleName, taskName, colors)
        String content = "${plainIconForOutcome(outcome)} ${outcome}${' ' * Math.max(1, 10 - outcome.length())}${Durations.format(duration)}"
        List<String> lines = [("${prefix}${Ansi.wrap(content, outcomeColor(outcome), colors)}" as String)]

        if (outcome == 'FAILED' && showCompileErrors && isJavaCompileTask(taskName) && tfe.result instanceof TaskFailureResult) {
            lines.addAll(compileFailureLines(prefix, (TaskFailureResult) tfe.result, colors))
        }
        printLines(lines)
    }

    /** Matches compileJava, compileTestJava, compile<SourceSet>Java, ... -- not compileGroovy etc. */
    protected static boolean isJavaCompileTask(String taskName) {
        return taskName ==~ /compile.*Java/
    }

    /**
     * Renders each javac error (not warning -- a failing compile's output is often mostly warnings,
     * and the error is the one line that actually needs to stand out) found in the failure's
     * description under the task's own prefix, instead of leaving it buried in the raw block Gradle
     * would otherwise only print once, at the very end of the build. There is no structured event for
     * this -- see {@link CompileDiagnostics}: {@code Failure.getProblems()} (the Tooling API's Problems
     * API) comes back empty for compile failures reached via {@code BuildEventsListenerRegistry} on
     * Gradle 9.5.1, even though the exception's own {@code description} already carries the full,
     * already-formatted diagnostic text.
     */
    protected List<String> compileFailureLines(String prefix, TaskFailureResult result, boolean colors) {
        List<CompileDiagnostics.Diagnostic> errors = allFailures(result.failures)
            .collectMany { Failure f -> CompileDiagnostics.parse(f.description) }
            .findAll { CompileDiagnostics.Diagnostic d -> d.severity == 'error' }
        if (errors.isEmpty()) return []

        List<String> lines = []
        String bar = Ansi.wrap('│', Ansi.GRAY, colors)
        errors.each { CompileDiagnostics.Diagnostic d ->
            lines << "${prefix}${bar} ${Ansi.wrap("✖ ${d.file}:${d.line}  error: ${d.message}", Ansi.RED, colors)}"
            d.context.each { ctx -> lines << "${prefix}${bar}     ${ctx}" }
        }
        return lines
    }

    protected static List<Failure> allFailures(List<Failure> failures) {
        List<Failure> all = []
        failures.each { Failure f -> all << f; all.addAll(allFailures(f.causes)) }
        return all
    }

    /** Called from every task's own doFirst -- BuildEventsListenerRegistry has no start event. */
    void taskStarted(String taskPath) {
        if (!taskLineEnabled) return
        String moduleName = moduleNameOf(taskPath)
        String taskName = taskNameOf(taskPath)
        boolean colors = colorsEnabled(taskColors)
        String prefix = Prefix.build(moduleName, taskName, colors)
        printLines(["${prefix}${Ansi.wrap('Started', Ansi.GRAY, colors)}"])
    }

    protected static String outcomeOf(def result) {
        if (result instanceof TaskFailureResult) return 'FAILED'
        if (result instanceof TaskSkippedResult) {
            String msg = ((TaskSkippedResult) result).skipMessage
            return msg ?: 'SKIPPED'
        }
        if (result instanceof TaskSuccessResult) {
            TaskSuccessResult success = (TaskSuccessResult) result
            if (success.upToDate) return 'UP-TO-DATE'
            if (success.fromCache) return 'FROM-CACHE'
            return 'SUCCESS'
        }
        return 'UNKNOWN'
    }

    protected static String plainIconForOutcome(String outcome) {
        switch (outcome) {
            case 'SUCCESS':
            case 'FROM-CACHE':
                return '✔'
            case 'FAILED':
                return '✖'
            default:
                return '⊖'
        }
    }

    protected static String outcomeColor(String outcome) {
        switch (outcome) {
            case 'SUCCESS':
            case 'FROM-CACHE':
                return Ansi.GREEN
            case 'FAILED':
                return Ansi.RED
            default:
                return Ansi.GRAY
        }
    }

    /**
     * "root" is only ever a fallback for the edge case where configure() was never called (e.g. the
     * plugin is disabled and somehow still reaches this path). Under normal operation this is always
     * overwritten with the real root project name, so single-project builds -- where every task path
     * looks like ":test" -- get the same module name here as {@link KestraLoggerPlugin#moduleNameOf}
     * derives from the live Project object for test lines.
     */
    protected volatile String rootProjectName = 'root'

    String moduleNameOf(String taskPath) {
        int lastColon = taskPath.lastIndexOf(':')
        String projectPath = lastColon > 0 ? taskPath.substring(0, lastColon) : ':'
        return projectPath == ':' ? rootProjectName : projectPath.replaceFirst('^:', '')
    }

    static String taskNameOf(String taskPath) {
        int lastColon = taskPath.lastIndexOf(':')
        return lastColon >= 0 ? taskPath.substring(lastColon + 1) : taskPath
    }

    // ------------------------------------------------------------------------------------------
    // Test reporting -- driven by KestraTestListener, one instance per Test task.
    // ------------------------------------------------------------------------------------------

    Object testStarted(String taskPath, String moduleName, TestDescriptor descriptor) {
        if (descriptor.className == null) return null // suite-level descriptor, not an individual test
        Object id = testIdSeq.incrementAndGet()
        RunningTest running = new RunningTest(
            id: id, taskPath: taskPath, moduleName: moduleName,
            className: descriptor.className, testName: descriptor.name,
            startMillis: System.currentTimeMillis()
        )
        runningTests[id] = running

        if (showStart) {
            boolean colors = colorsEnabled(taskColors)
            String prefix = Prefix.build(moduleName, taskNameOf(taskPath), colors)
            printLines(["${prefix}${Ansi.wrap('▶', Ansi.GRAY, colors)} ${testLabel(descriptor)}"])
        }
        return id
    }

    void testOutput(Object id, TestOutputEvent event) {
        if (id == null) return
        RunningTest running = runningTests[id]
        if (running == null) return
        synchronized (running) {
            event.message.readLines().each { line ->
                if (running.outputLines.size() < 500) {
                    running.outputLines << line
                }
            }
        }
    }

    void testFinished(Object id, String taskPath, String moduleName, TestDescriptor descriptor, TestResult result,
                       List<String> testSourceRoots) {
        if (descriptor.className == null) return
        RunningTest running = id != null ? runningTests.remove(id) : null
        long duration = Math.max(0L, result.endTime - result.startTime)
        boolean colors = colorsEnabled(taskColors)
        String label = testLabel(descriptor)
        String prefix = Prefix.build(moduleName, taskNameOf(taskPath), colors)

        List<String> lines = []
        switch (result.resultType) {
            case TestResult.ResultType.SUCCESS:
                lines << "${prefix}${Ansi.wrap('✔', Ansi.GREEN, colors)} ${label}${durationSuffix(duration, colors)}"
                if (showPassedStandardStreams) lines.addAll(streamLines(prefix, label, running, colors))
                break
            case TestResult.ResultType.SKIPPED:
                String skipContent = "⊖ ${label}  ${Durations.format(duration)}"
                lines << "${prefix}${Ansi.wrap(skipContent, Ansi.GRAY, colors)}"
                if (showSkippedStandardStreams) lines.addAll(streamLines(prefix, label, running, colors))
                break
            case TestResult.ResultType.FAILURE:
            default:
                String failContent = "✖ ${label}  ${Durations.format(duration)}"
                lines << "${prefix}${Ansi.wrap(failContent, Ansi.RED, colors)}"
                if (showFailedStandardStreams) lines.addAll(streamLines(prefix, label, running, colors))
                if (showExceptions) lines.addAll(exceptionLines(prefix, label, result, colors))
                recordFailure(moduleName, label, result, testSourceRoots, duration)
                break
        }
        printLines(lines)

        if (duration >= slowThreshold) {
            slowEntries << new GithubSummary.SlowEntry(label: "${moduleName} › ${label}", durationMillis: duration)
        }
    }

    /** Called from the root (task-level) afterSuite -- prints the per-task totals line. */
    void taskTestsFinished(String taskPath, String moduleName, TestResult result) {
        boolean colors = colorsEnabled(taskColors)
        String prefix = Prefix.build(moduleName, taskNameOf(taskPath), colors)
        long duration = Math.max(0L, result.endTime - result.startTime)

        GithubSummary.ModuleStats stats = perModuleStats.computeIfAbsent(moduleName) { new GithubSummary.ModuleStats(moduleName: moduleName) }
        synchronized (stats) {
            stats.total += result.testCount
            stats.passed += result.successfulTestCount
            stats.failed += result.failedTestCount
            stats.skipped += result.skippedTestCount
            stats.durationMillis += duration
        }

        String line = "${prefix}${Ansi.wrap('═', Ansi.CYAN, colors)} ${result.testCount} total · " +
            "${Ansi.wrap(result.successfulTestCount + ' passed', Ansi.GREEN, colors)} · " +
            "${Ansi.wrap(result.failedTestCount + ' failed', Ansi.RED, colors)} · " +
            "${Ansi.wrap(result.skippedTestCount + ' skipped', Ansi.GRAY, colors)} in " +
            Durations.formatColored(duration, slowThreshold, colors)
        printLines([line])
    }

    /** Called once per Test task, from its own doFirst -- plain values only, config-cache safe. */
    void registerHeapLogDir(String taskPath, File gcLogDir) {
        heapLogDirs[taskPath] = gcLogDir
        // Baseline for tickHeap()'s debounce, so the first "current heap" line waits a full
        // heap.interval after the task starts rather than firing on the very next tick.
        heapLastAnnounceMillis[taskPath] = System.currentTimeMillis()
    }

    /**
     * Reads back whatever GC log the task's forked worker(s) wrote, regardless of whether the task
     * ended up passing or failing. {@code heapLogDirs} only has an entry for a task that actually ran
     * this build (registered from doFirst), so a skipped/cached/up-to-date task is a no-op here.
     */
    protected void processHeapUsage(String taskPath) {
        heapLastAnnounceMillis.remove(taskPath)
        if (!heapEnabled) return
        File gcLogDir = heapLogDirs.remove(taskPath)
        if (gcLogDir == null) return

        String moduleName = moduleNameOf(taskPath)
        HeapUsage.Sample peak = HeapUsage.peakUsage(gcLogDir)

        double percent = peak.percent()
        if (percent >= 0) {
            GithubSummary.ModuleStats stats = perModuleStats.computeIfAbsent(moduleName) { new GithubSummary.ModuleStats(moduleName: moduleName) }
            synchronized (stats) {
                if (percent > percentOf(stats.peakHeapUsedBytes, stats.peakHeapCapacityBytes)) {
                    stats.peakHeapUsedBytes = peak.usedBytes
                    stats.peakHeapCapacityBytes = peak.capacityBytes
                }
            }
        }

        boolean colors = colorsEnabled(taskColors)
        String prefix = Prefix.build(moduleName, taskNameOf(taskPath), colors)
        printLines(["${prefix}${Ansi.wrap('◆', Ansi.CYAN, colors)} peak heap ${formatHeap(peak.usedBytes, peak.capacityBytes)}"])
    }

    /** Renders "n/a", or "<used> / <capacity> (<percent>%)" -- the percentage, not the raw bytes, is the headline signal. */
    protected static String formatHeap(long usedBytes, long capacityBytes) {
        double percent = percentOf(usedBytes, capacityBytes)
        if (percent < 0) return 'n/a'
        return "${Bytes.format(usedBytes)} / ${Bytes.format(capacityBytes)} (${Math.round(percent * 100)}%)"
    }

    protected static double percentOf(long usedBytes, long capacityBytes) {
        return (usedBytes < 0 || capacityBytes <= 0) ? -1d : usedBytes / (double) capacityBytes
    }

    protected void recordFailure(String moduleName, String label, TestResult result, List<String> testSourceRoots, long duration) {
        String fullLabel = "${moduleName} › ${label}"
        failureLabels << "${fullLabel} ${Durations.format(duration)}"

        if (githubAnnotations && GithubAnnotations.isGithubActions()) {
            Throwable ex = result.exceptions ? result.exceptions[0] : null
            String message = ex ? "${ex.class.name}: ${ex.message}" : 'Test failed'
            List loc = ex ? SourceLocator.locate(testSourceRoots, ex.stackTrace) : null
            GithubAnnotations.error(fullLabel, message, loc ? loc[0] as String : null, loc ? loc[1] as Integer : null)
        }
    }

    protected String durationSuffix(long duration, boolean colors) {
        return '  ' + Durations.formatColored(duration, slowThreshold, colors)
    }

    protected String testLabel(TestDescriptor descriptor) {
        return "${namespaceOf(descriptor.className)} › ${simpleNameOf(descriptor.className)} › ${descriptor.name}"
    }

    protected static String simpleNameOf(String className) {
        int dot = className.lastIndexOf('.')
        return dot >= 0 ? className.substring(dot + 1) : className
    }

    /**
     * "io.kestra.core.runners" -> "i.k.c.runners": every segment but the last is abbreviated, unless
     * fullNamespace is set, in which case the package prints in full.
     */
    protected String namespaceOf(String className) {
        int dot = className.lastIndexOf('.')
        if (dot < 0) return ''
        String pkg = className.substring(0, dot)
        if (fullNamespace) return pkg
        List<String> segments = pkg.split('\\.') as List<String>
        if (segments.size() <= 1) return pkg
        List<String> abbreviated = segments[0..-2].collect { it.isEmpty() ? it : it.substring(0, 1) }
        abbreviated << segments[-1]
        return abbreviated.join('.')
    }

    protected List<String> streamLines(String prefix, String label, RunningTest running, boolean colors) {
        if (running == null || running.outputLines.isEmpty()) return []
        List<String> result = []
        synchronized (running) {
            running.outputLines.each { line ->
                result << "${prefix}${Ansi.wrap('│', Ansi.GRAY, colors)} ${label} │ ${line}"
            }
        }
        return result
    }

    protected List<String> exceptionLines(String prefix, String label, TestResult result, boolean colors) {
        List<String> lines = []
        String bar = Ansi.wrap('│', Ansi.GRAY, colors)
        result.exceptions.each { Throwable ex ->
            lines << "${prefix}${bar} ${label} │ ${Ansi.wrap(ex.class.name + ': ' + ex.message, Ansi.RED, colors)}"
            eachFrame(ex.stackTrace) { String frameLine ->
                lines << "${prefix}${bar} ${label} │     at ${frameLine}"
            }
            Throwable cause = ex.cause
            while (showCauses && cause != null && cause != ex) {
                lines << "${prefix}${bar} ${label} │   Caused by: ${cause.class.name}: ${cause.message}"
                eachFrame(cause.stackTrace) { String frameLine ->
                    lines << "${prefix}${bar} ${label} │     at ${frameLine}"
                }
                cause = cause.cause
            }
        }
        return lines
    }

    protected void eachFrame(StackTraceElement[] trace, Closure<Void> emit) {
        if (trace == null) return
        int shown = 0
        int hidden = 0
        for (StackTraceElement frame : trace) {
            boolean filtered = !showFullStackTraces && stackTraceFilters.any { frame.className.startsWith(it) }
            if (filtered) {
                hidden++
                continue
            }
            emit(frame.toString())
            shown++
        }
        if (hidden > 0) {
            emit("... ${hidden} more")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Heartbeat -- tests JUnit only reports on completion; this announces ones still running.
    // ------------------------------------------------------------------------------------------

    protected void tick() {
        if (heartbeatEnabled) tickHeartbeat()
        if (heapEnabled && heapIntervalMillis > 0) tickHeap()
    }

    protected void tickHeartbeat() {
        long now = System.currentTimeMillis()
        boolean colors = colorsEnabled(taskColors)
        // Snapshot to avoid mutating the live map while iterating.
        List<RunningTest> candidates = new ArrayList<>(runningTests.values())
        candidates.each { RunningTest running ->
            long elapsed = now - running.startMillis
            if (elapsed < heartbeatThreshold) return
            if (running.lastHeartbeatMillis != 0 && (now - running.lastHeartbeatMillis) < heartbeatInterval) return

            running.lastHeartbeatMillis = now
            String label = testLabel2(running)
            String prefix = Prefix.build(running.moduleName, taskNameOf(running.taskPath), colors)
            List<String> lines = []
            lines << "${prefix}${Ansi.wrap('⏱', Ansi.YELLOW, colors)}  ${label} running since ${Durations.format(elapsed)}"
            if (heartbeatDisplayOutput) {
                List<String> fresh
                synchronized (running) {
                    int total = running.outputLines.size()
                    int from = running.streamedLines
                    fresh = from < total ? running.outputLines.subList(from, total) : []
                    running.streamedLines = total
                }
                String bar = Ansi.wrap('⏱', Ansi.YELLOW, colors)
                fresh.each { line -> lines << "${prefix}${bar} ${Ansi.wrap('│', Ansi.GRAY, colors)} ${line}" }
            }
            printLines(lines)
        }
    }

    protected String testLabel2(RunningTest running) {
        return "${namespaceOf(running.className)} › ${simpleNameOf(running.className)} › ${running.testName}"
    }

    /**
     * Prints a "current heap" line for every still-running Test task, on the same cadence as
     * heap.interval, using whatever GC sample its forked worker(s) most recently logged -- not the
     * peak-so-far line, which only prints once the task finishes.
     */
    protected void tickHeap() {
        long now = System.currentTimeMillis()
        boolean colors = colorsEnabled(taskColors)
        // Snapshot to avoid mutating the live map while iterating.
        Map<String, File> candidates = new HashMap<>(heapLogDirs)
        candidates.each { String taskPath, File gcLogDir ->
            Long last = heapLastAnnounceMillis[taskPath]
            if (last != null && (now - last) < heapIntervalMillis) return

            heapLastAnnounceMillis[taskPath] = now
            HeapUsage.Sample current = HeapUsage.latestUsage(gcLogDir)
            String moduleName = moduleNameOf(taskPath)
            String prefix = Prefix.build(moduleName, taskNameOf(taskPath), colors)
            printLines(["${prefix}${Ansi.wrap('◆', Ansi.CYAN, colors)} current heap ${formatHeap(current.usedBytes, current.capacityBytes)}"])
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shutdown -- build-end summary + GitHub job summary.
    // ------------------------------------------------------------------------------------------

    @Override
    void close() {
        if (tickExecutor != null) {
            tickExecutor.shutdownNow()
        }
        if (perModuleStats.isEmpty()) return

        boolean colors = colorsEnabled(taskColors)
        String prefix = Prefix.build('test', 'summary', colors)

        int total = 0, passed = 0, failed = 0, skipped = 0
        long duration = 0
        perModuleStats.values().each { GithubSummary.ModuleStats m ->
            total += m.total
            passed += m.passed
            failed += m.failed
            skipped += m.skipped
            duration += m.durationMillis
        }

        String bar = Ansi.wrap('│', Ansi.GRAY, colors)

        List<String> lines = []
        lines << "${prefix}${Ansi.wrap('═', Ansi.CYAN, colors)} ${total} total · " +
            "${Ansi.wrap(passed + ' passed', Ansi.GREEN, colors)} · " +
            "${Ansi.wrap(failed + ' failed', Ansi.RED, colors)} · " +
            "${Ansi.wrap(skipped + ' skipped', Ansi.GRAY, colors)} › " +
            Durations.formatColored(duration, slowThreshold, colors)

        if (heapEnabled) {
            lines << "${prefix}${Ansi.wrap('◆', Ansi.CYAN, colors)} Peak heap:"
            perModuleStats.values().sort { it.moduleName }.each { GithubSummary.ModuleStats m ->
                lines << "${prefix}${bar} ${m.moduleName}: ${formatHeap(m.peakHeapUsedBytes, m.peakHeapCapacityBytes)}"
            }
        }

        if (failureLabels) {
            lines << "${prefix}${Ansi.wrap('◆', Ansi.CYAN, colors)} Failed tests:"
            failureLabels.each { lines << "${prefix}${bar} ${it}" }
        }
        printLines(lines)

        if (githubJobSummary && GithubAnnotations.isGithubActions()) {
            GithubSummary.write(new ArrayList<>(perModuleStats.values()), new ArrayList<>(failureLabels), new ArrayList<>(slowEntries), slowThreshold)
        }
    }

    /**
     * Prints a block as one joined string in a single write, under a single lock. The lock alone
     * already stops two of our own blocks from interleaving -- callers always pass a whole logical
     * block (header, streamed output, full stack trace) in one call -- but N separate println()
     * calls still left N separate underlying writes, each a window for something outside this
     * plugin (a differently-threaded writer to the same stream) to land a line in between. One
     * write() call closes that window too.
     */
    protected void printLines(List<String> lines) {
        if (lines.isEmpty()) return
        String block = lines.join(System.lineSeparator()) + System.lineSeparator()
        synchronized (printLock) {
            out.print(block)
            out.flush()
        }
    }

    protected static class RunningTest {
        Object id
        String taskPath
        String moduleName
        String className
        String testName
        long startMillis
        long lastHeartbeatMillis = 0
        int streamedLines = 0
        final List<String> outputLines = Collections.synchronizedList(new ArrayList<>())
    }
}
