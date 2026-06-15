package io.kestra.gradle.scan

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.ExampleInfo
import io.kestra.gradle.model.FieldInfo
import io.kestra.gradle.model.PackageInfo

import java.lang.annotation.Annotation
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Reflects over the compiled plugin classes to build {@link ClassInfo}/{@link PackageInfo}.
 * Annotations are matched by fully-qualified name so the plugin needs no compile dependency
 * on Kestra. Classes are loaded without initialization to avoid running static blocks.
 */
class ClassScanner {

    static final String SCHEMA = 'io.swagger.v3.oas.annotations.media.Schema'
    static final String PLUGIN = 'io.kestra.core.models.annotations.Plugin'
    static final String EXAMPLE = 'io.kestra.core.models.annotations.Example'
    static final String PLUGIN_PROPERTY = 'io.kestra.core.models.annotations.PluginProperty'
    static final String PLUGIN_SUBGROUP = 'io.kestra.core.models.annotations.PluginSubGroup'

    static final String TASK = 'io.kestra.core.models.tasks.Task'
    static final List<String> TRIGGER = [
        'io.kestra.core.models.triggers.AbstractTrigger',
        'io.kestra.core.models.triggers.Trigger'
    ]
    static final String OUTPUT = 'io.kestra.core.models.tasks.Output'

    final ClassLoader loader

    ClassScanner(ClassLoader loader) {
        this.loader = loader
    }

    /** Result holder: discovered classes and per-package info. */
    static class Result {
        List<ClassInfo> classes = []
        Map<String, PackageInfo> packages = [:]
    }

    Result scan(List<File> classDirs) {
        Result result = new Result()
        Set<String> packagesWithPackageInfo = []

        classDirs.findAll { it.exists() }.each { File dir ->
            dir.traverse(type: groovy.io.FileType.FILES, nameFilter: ~/.*\.class/) { File classFile ->
                String relative = dir.toURI().relativize(classFile.toURI()).path
                String name = relative[0..-7].replace('/', '.') // strip ".class"

                if (name.endsWith('package-info')) {
                    packagesWithPackageInfo << name[0..-('.package-info'.length() + 1)]
                    return
                }

                Class<?> clazz = loadClass(name)
                if (clazz == null) {
                    return
                }
                try {
                    result.classes << toClassInfo(clazz)
                } catch (Throwable ignored) {
                    // a class whose dependencies cannot be resolved is skipped, not fatal
                }
            }
        }

        // Build per-package info for every package that holds a scanned class.
        result.classes.collect { it.packageName }.toSet().each { String pkg ->
            PackageInfo info = new PackageInfo(name: pkg)
            info.hasPackageInfo = packagesWithPackageInfo.contains(pkg)
            ClassInfo representative = result.classes.find { it.packageName == pkg }
            readSubGroup(pkg, representative, info)
            result.packages[pkg] = info
        }

        return result
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, loader)
        } catch (Throwable ignored) {
            return null
        }
    }

    private ClassInfo toClassInfo(Class<?> clazz) {
        ClassInfo info = new ClassInfo()
        info.fqcn = clazz.name
        info.packageName = clazz.package?.name ?: ''
        info.simpleName = clazz.simpleName
        info.isInterface = clazz.isInterface()
        info.isAbstract = Modifier.isAbstract(clazz.modifiers)
        info.kind = kindOf(clazz)

        Annotation schema = find(clazz, SCHEMA)
        if (schema != null) {
            info.hasSchema = true
            info.schemaTitle = attrString(schema, 'title')
            info.schemaDescription = attrString(schema, 'description')
        }

        Annotation plugin = find(clazz, PLUGIN)
        if (plugin != null) {
            info.hasPluginAnnotation = true
            Object examples = attr(plugin, 'examples')
            if (examples != null) {
                examples.each { Object ex -> info.examples << toExample((Annotation) ex) }
            }
        }

        clazz.declaredFields.findAll { !it.synthetic }.each { Field field ->
            info.fields << toFieldInfo(field)
        }

        return info
    }

    private static ExampleInfo toExample(Annotation ex) {
        ExampleInfo example = new ExampleInfo()
        example.title = attrString(ex, 'title')
        example.full = (attr(ex, 'full') ?: false) as boolean
        example.lang = attrString(ex, 'lang')
        Object code = attr(ex, 'code')
        if (code instanceof String[]) {
            example.code = ((String[]) code).join('\n')
        } else {
            example.code = code?.toString() ?: ''
        }
        return example
    }

    private static FieldInfo toFieldInfo(Field field) {
        FieldInfo info = new FieldInfo()
        info.name = field.name
        info.isStatic = Modifier.isStatic(field.modifiers)
        info.isTransient = Modifier.isTransient(field.modifiers)

        Annotation schema = find(field, SCHEMA)
        if (schema != null) {
            info.hasSchema = true
            info.schemaTitle = attrString(schema, 'title')
            info.schemaDescription = attrString(schema, 'description')
        }

        Annotation pp = find(field, PLUGIN_PROPERTY)
        if (pp != null) {
            info.hasPluginProperty = true
            info.pluginPropertyGroup = attrString(pp, 'group')
            info.pluginPropertySecret = (attr(pp, 'secret') ?: false) as boolean
        }

        return info
    }

    private void readSubGroup(String pkg, ClassInfo representative, PackageInfo info) {
        Annotation subGroup = packageInfoAnnotation(pkg, representative)
        if (subGroup == null) {
            return
        }
        info.hasSubGroup = true
        Object categories = attr(subGroup, 'categories')
        if (categories != null) {
            categories.each { Object category ->
                info.subGroupCategories << ((Enum) category).name()
            }
        }
    }

    /**
     * Read the {@code @PluginSubGroup} for a package. Loads the {@code package-info} class
     * directly (its annotations are the package annotations); falls back to the package of a
     * representative class.
     */
    private Annotation packageInfoAnnotation(String pkg, ClassInfo representative) {
        try {
            Class<?> packageInfo = Class.forName("${pkg}.package-info", false, loader)
            Annotation found = packageInfo.declaredAnnotations.find { it.annotationType().name == PLUGIN_SUBGROUP }
            if (found != null) {
                return found
            }
        } catch (Throwable ignored) {
            // fall through to the package-based lookup
        }
        try {
            Package p = representative == null ? null : Class.forName(representative.fqcn, false, loader).package
            return p?.declaredAnnotations?.find { it.annotationType().name == PLUGIN_SUBGROUP }
        } catch (Throwable ignored) {
            return null
        }
    }

    private ClassInfo.Kind kindOf(Class<?> clazz) {
        if (isAssignableToName(clazz, TASK)) {
            return ClassInfo.Kind.TASK
        }
        if (TRIGGER.any { isAssignableToName(clazz, it) }) {
            return ClassInfo.Kind.TRIGGER
        }
        if (isAssignableToName(clazz, OUTPUT)) {
            return ClassInfo.Kind.OUTPUT
        }
        return ClassInfo.Kind.OTHER
    }

    /** Walk the type hierarchy by name so we never need the Kestra classes on our classpath. */
    private static boolean isAssignableToName(Class<?> clazz, String target) {
        Set<Class<?>> seen = new HashSet<>()
        Deque<Class<?>> queue = new ArrayDeque<>()
        queue.add(clazz)
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll()
            if (current == null || !seen.add(current)) {
                continue
            }
            if (current.name == target) {
                return true
            }
            if (current.superclass != null) {
                queue.add(current.superclass)
            }
            current.interfaces.each { queue.add(it) }
        }
        return false
    }

    private static Annotation find(AnnotatedElement element, String fqn) {
        return element.declaredAnnotations.find { it.annotationType().name == fqn }
    }

    private static Object attr(Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation)
        } catch (Exception ignored) {
            return null
        }
    }

    private static String attrString(Annotation annotation, String name) {
        Object value = attr(annotation, name)
        return value == null ? null : value.toString()
    }
}
