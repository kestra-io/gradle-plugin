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

    /**
     * The {@code group} declared in {@code metadata/index.yaml}, populated when the model is
     * built. It is the authoritative plugin root: tasks may live only in a subpackage, so the
     * common-prefix heuristic alone would mistake the subpackage for the root.
     */
    String declaredRootPackage

    List<ClassInfo> tasksAndTriggers() {
        return classes.findAll { it.isConcreteTaskOrTrigger() }
    }

    /** Concrete task, trigger, task runner and log exporter classes. */
    List<ClassInfo> documentablePlugins() {
        return classes.findAll { it.isDocumentablePlugin() }
    }

    /** Packages that contain at least one documentable plugin class. */
    Set<String> packagesWithPlugins() {
        return documentablePlugins().collect { it.packageName }.toSet()
    }

    /**
     * Root package: the longest common dot-delimited prefix across all packages that
     * contain a plugin class. For a single-package plugin this is that package.
     */
    String rootPackage() {
        Set<String> pkgs = packagesWithPlugins()
        if (pkgs.isEmpty()) {
            return null
        }
        // Prefer the declared group when every task package sits under it: this is the real
        // plugin root even when all concrete tasks live in a single subpackage.
        if (declaredRootPackage != null && !declaredRootPackage.trim().isEmpty()
            && pkgs.every { it == declaredRootPackage || it.startsWith(declaredRootPackage + '.') }) {
            return declaredRootPackage
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

    /** Source {@code .java} file for a class, mapping nested classes to their top-level file. */
    File sourceFileFor(ClassInfo info) {
        if (sourceRoot == null) {
            return null
        }
        String topLevel = info.fqcn.contains('$') ? info.fqcn.substring(0, info.fqcn.indexOf('$')) : info.fqcn
        File f = new File(sourceRoot, topLevel.replace('.', '/') + '.java')
        return f.exists() ? f : null
    }
}
