package io.kestra.gradle.model

/**
 * The full input to the rule engine: the scanned classes, per-package metadata, and the
 * source/resource roots. Rules are pure functions over this object, which keeps them
 * unit-testable without compiling Kestra classes or hitting the network.
 */
class PluginModel {
    List<ClassInfo> classes = []
    Map<String, PackageInfo> packages = [:]
    File resourceRoot
    File sourceRoot

    List<ClassInfo> tasksAndTriggers() {
        return classes.findAll { it.isConcreteTaskOrTrigger() }
    }

    /** Packages that contain at least one concrete task or trigger. */
    Set<String> packagesWithTasksOrTriggers() {
        return tasksAndTriggers().collect { it.packageName }.toSet()
    }

    /**
     * Root package: the longest common dot-delimited prefix across all packages that
     * contain a task or trigger. For a single-package plugin this is that package.
     */
    String rootPackage() {
        Set<String> pkgs = packagesWithTasksOrTriggers()
        if (pkgs.isEmpty()) {
            return null
        }
        if (pkgs.size() == 1) {
            return pkgs.first()
        }
        List<List<String>> segmented = pkgs.collect { it.split(/\./) as List }
        List<String> common = []
        int min = segmented*.size().min()
        for (int i = 0; i < min; i++) {
            String seg = segmented[0][i]
            if (segmented.every { it[i] == seg }) {
                common << seg
            } else {
                break
            }
        }
        return common.join('.')
    }

    File metadataDir() { new File(resourceRoot, 'metadata') }
    File iconsDir() { new File(resourceRoot, 'icons') }
    File docDir() { new File(resourceRoot, 'doc') }

    /** Source {@code .java} file for a top-level class, or null when not found. */
    File sourceFileFor(ClassInfo info) {
        if (sourceRoot == null) {
            return null
        }
        File f = new File(sourceRoot, info.fqcn.replace('.', '/') + '.java')
        return f.exists() ? f : null
    }
}
