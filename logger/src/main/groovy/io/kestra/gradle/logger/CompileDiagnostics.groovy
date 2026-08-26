package io.kestra.gradle.logger

import java.util.regex.Pattern

/**
 * Parses the per-error block javac writes into a failed {@code JavaCompile} task's
 * {@code CompilationFailedException} description -- the same text javac would otherwise print to
 * stderr -- into individual file/line/severity/message entries with their source-snippet context.
 *
 * There is no structured event for this: {@code Failure.getProblems()} (the Tooling API's Problems
 * API) comes back empty for compile failures reached via {@code BuildEventsListenerRegistry} on
 * Gradle 9.5.1, even though the exception's own {@code description} already carries the full,
 * already-formatted diagnostic text. Parsing that text is the only way to render it ourselves.
 *
 * Parses both {@code error} and {@code warning} entries -- filtering to errors only is the caller's
 * job (see {@code LoggerService.compileFailureLines}), not this parser's.
 */
class CompileDiagnostics {

    private static final Pattern HEADER = ~/^(.+):(\d+): (error|warning): (.*)$/
    private static final Pattern STACK_FRAME = ~/^\s*at\s/
    private static final Pattern SUMMARY = ~/^\d+ (error|warning)s?$/

    static class Diagnostic {
        String file
        int line
        String severity
        String message
        List<String> context = []
    }

    /**
     * @return diagnostics found in {@code description}, or an empty list when it doesn't contain any
     * -- which is also what happens for any unrelated failure's description, so callers can call this
     * unconditionally rather than first checking whether a given failure is a compile failure.
     */
    static List<Diagnostic> parse(String description) {
        if (description == null) return []

        List<Diagnostic> diagnostics = []
        Diagnostic current = null
        // A stack-trace-shaped line marks where the exception's own stack trace starts -- everything
        // from there on is Gradle/javac internals, not compiler diagnostics.
        for (String line : description.readLines()) {
            if (STACK_FRAME.matcher(line).find()) break

            def header = HEADER.matcher(line)
            if (header.matches()) {
                current = new Diagnostic(file: header.group(1), line: header.group(2) as int,
                    severity: header.group(3), message: header.group(4))
                diagnostics << current
                continue
            }
            if (SUMMARY.matcher(line.trim()).matches()) continue
            if (current != null) current.context << line
        }
        return diagnostics
    }
}
