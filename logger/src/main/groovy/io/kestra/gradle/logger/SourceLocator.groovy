package io.kestra.gradle.logger

/**
 * Best-effort mapping from a stack trace to a "file:line" pair for GitHub annotations. Looks for
 * the first frame whose class lives under one of the module's own test source roots, so annotations
 * point at the test's own assertion rather than into a library or the JDK.
 */
class SourceLocator {

    /**
     * @param srcRoots absolute paths to the module's test source directories (e.g. src/test/java)
     * @return a [file, line] pair, or null when no frame could be resolved to a real file
     */
    static List locate(List<String> srcRoots, StackTraceElement[] trace) {
        if (srcRoots == null || srcRoots.isEmpty() || trace == null) return null
        for (StackTraceElement frame : trace) {
            String className = frame.className
            if (className == null || frame.lineNumber < 0) continue
            // Nested/anonymous classes (Foo$1, Foo$Bar) live in the same file as their enclosing class.
            String topLevel = className.contains('$') ? className.substring(0, className.indexOf('$')) : className
            String relativePath = topLevel.replace('.', '/') + '.java'
            for (String root : srcRoots) {
                File candidate = new File(root, relativePath)
                if (candidate.isFile()) {
                    return [candidate.absolutePath, frame.lineNumber]
                }
            }
        }
        return null
    }
}
