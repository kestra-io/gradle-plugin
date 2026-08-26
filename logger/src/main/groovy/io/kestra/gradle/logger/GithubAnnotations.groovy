package io.kestra.gradle.logger

/**
 * Emits GitHub Actions workflow-command annotations (::error / ::warning). Only meaningful when
 * running inside a GitHub Actions job (GITHUB_ACTIONS=true); callers are expected to check that
 * before invoking this class.
 *
 * https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#setting-an-error-message
 */
class GithubAnnotations {

    static boolean isGithubActions() {
        return 'true' == System.getenv('GITHUB_ACTIONS')
    }

    static void error(String title, String message, String file, Integer line) {
        emit('error', title, message, file, line)
    }

    static void warning(String title, String message, String file, Integer line) {
        emit('warning', title, message, file, line)
    }

    private static void emit(String level, String title, String message, String file, Integer line) {
        StringBuilder params = new StringBuilder("title=${escapeProperty(title)}")
        if (file != null) {
            params << ",file=${escapeProperty(file)}"
            if (line != null) {
                params << ",line=${line}"
            }
        }
        println "::${level} ${params}::${escapeData(message)}"
    }

    // Workflow-command property values: escape %, \r, \n, and ','.
    private static String escapeProperty(String value) {
        return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A').replace(',', '%2C').replace(':', '%3A')
    }

    // Workflow-command data values: escape %, \r, \n.
    private static String escapeData(String value) {
        return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    }
}
