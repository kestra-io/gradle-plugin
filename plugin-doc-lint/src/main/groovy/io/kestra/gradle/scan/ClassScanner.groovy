package io.kestra.gradle.scan

import io.kestra.gradle.model.ClassInfo
import io.kestra.gradle.model.ExampleInfo
import io.kestra.gradle.model.FieldInfo
import io.kestra.gradle.model.PackageInfo

import java.lang.annotation.Annotation
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.Method
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

    static final String JSON_IGNORE = 'com.fasterxml.jackson.annotation.JsonIgnore'

    static final String TASK = 'io.kestra.core.models.tasks.Task'
    static final List<String> TRIGGER = [
        'io.kestra.core.models.triggers.AbstractTrigger',
        'io.kestra.core.models.triggers.Trigger'
    ]
    static final String TASK_RUNNER = 'io.kestra.core.models.tasks.runners.TaskRunner'
    static final String LOG_EXPORTER = 'io.kestra.core.models.tasks.logs.LogExporter'
    static final String OUTPUT = 'io.kestra.core.models.tasks.Output'

    final ClassLoader loader

    ClassScanner(ClassLoader loader) {
        this.loader = loader
    }

    /** Result holder: discovered classes and per-package info. */
    static class Result {
        List<ClassInfo> classes = []
        Map<String, PackageInfo> packages = [:]
        /** Classes that could not be loaded or inspected; reported so failures are not silent. */
        int skipped = 0
    }

    Result scan(List<File> classDirs) {
        Result result = new Result()
        Set<String> packagesWithPackageInfo = []
        Set<String> ownClasses = collectClassNames(classDirs)

        classDirs.findAll { it.exists() }.each { File dir ->
            dir.traverse(type: groovy.io.FileType.FILES, nameFilter: ~/.*\.class/) { File classFile ->
                String relative = dir.toURI().relativize(classFile.toURI()).path
                String name = relative[0..-7].replace('/', '.') // strip ".class"

                if (name.endsWith('package-info')) {
                    // Default (root) package has no prefix to strip.
                    String pkg = (name == 'package-info') ? '' : name[0..-('.package-info'.length() + 1)]
                    packagesWithPackageInfo << pkg
                    return
                }

                Class<?> clazz = loadClass(name)
                if (clazz == null) {
                    result.skipped++
                    return
                }
                try {
                    result.classes << toClassInfo(clazz, ownClasses)
                } catch (Throwable ignored) {
                    // a class whose dependencies cannot be resolved is skipped, not fatal
                    result.skipped++
                }
            }
        }

        result.classes.collect { it.packageName }.toSet().each { String pkg ->
            PackageInfo info = new PackageInfo(name: pkg)
            info.hasPackageInfo = packagesWithPackageInfo.contains(pkg)
            ClassInfo representative = result.classes.find { it.packageName == pkg }
            readSubGroup(pkg, representative, info)
            result.packages[pkg] = info
        }

        return result
    }

    private static Set<String> collectClassNames(List<File> classDirs) {
        Set<String> names = []
        classDirs.findAll { it.exists() }.each { File dir ->
            dir.traverse(type: groovy.io.FileType.FILES, nameFilter: ~/.*\.class/) { File classFile ->
                String name = dir.toURI().relativize(classFile.toURI()).path[0..-7].replace('/', '.')
                if (!name.endsWith('package-info')) {
                    names << name
                }
            }
        }
        return names
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, loader)
        } catch (Throwable ignored) {
            return null
        }
    }

    private ClassInfo toClassInfo(Class<?> clazz, Set<String> ownClasses) {
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

        collectDeclaredFields(clazz, ownClasses).each { Field field ->
            info.fields << toFieldInfo(clazz, field)
        }

        return info
    }

    /**
     * Declared fields of the class and any superclasses in the plugin's own output. The walk stops
     * at the first class outside the plugin (a framework base), so framework fields are not linted.
     * A field name seen lower in the hierarchy shadows the same name higher up.
     */
    private static List<Field> collectDeclaredFields(Class<?> clazz, Set<String> ownClasses) {
        List<Field> fields = []
        Set<String> seen = new HashSet<>()
        Class<?> current = clazz
        while (current != null && ownClasses.contains(current.name)) {
            current.declaredFields.findAll { !it.synthetic && seen.add(it.name) }.each { fields << it }
            current = current.superclass
        }
        return fields
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

    private static FieldInfo toFieldInfo(Class<?> owner, Field field) {
        FieldInfo info = new FieldInfo()
        info.name = field.name
        info.declaringClassName = field.declaringClass.name
        info.isStatic = Modifier.isStatic(field.modifiers)
        info.isTransient = Modifier.isTransient(field.modifiers)
        info.isProperty = isProperty(owner, field)

        // @Schema and @PluginProperty may sit on the field or on its getter (interface-based config), so check both.
        Annotation schema = find(field, SCHEMA) ?: accessorAnnotation(owner, field.name, SCHEMA)
        if (schema != null) {
            info.hasSchema = true
            info.schemaTitle = attrString(schema, 'title')
            info.schemaDescription = attrString(schema, 'description')
        }

        Annotation pp = find(field, PLUGIN_PROPERTY) ?: accessorAnnotation(owner, field.name, PLUGIN_PROPERTY)
        if (pp != null) {
            info.hasPluginProperty = true
            info.pluginPropertyGroup = attrString(pp, 'group')
            info.pluginPropertySecret = (attr(pp, 'secret') ?: false) as boolean
        }

        return info
    }

    /** The annotation {@code fqn} on the field's getter (getX/isX), searched across superclasses and interfaces. */
    private static Annotation accessorAnnotation(Class<?> owner, String fieldName, String fqn) {
        if (!fieldName) {
            return null
        }
        String cap = fieldName[0].toUpperCase() + fieldName.substring(1)
        Set<String> names = ["get${cap}".toString(), "is${cap}".toString()] as Set
        Set<Class<?>> seen = new HashSet<>()
        Deque<Class<?>> queue = new ArrayDeque<>()
        queue.add(owner)
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll()
            if (current == null || !seen.add(current)) {
                continue
            }
            try {
                for (Method m : current.declaredMethods) {
                    if (m.parameterCount == 0 && names.contains(m.name)) {
                        Annotation a = m.declaredAnnotations.find { it.annotationType().name == fqn }
                        if (a != null) {
                            return a
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (current.superclass != null) {
                queue.add(current.superclass)
            }
            current.interfaces.each { queue.add(it) }
        }
        return null
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
        if (isAssignableToName(clazz, TASK_RUNNER)) {
            return ClassInfo.Kind.TASK_RUNNER
        }
        if (isAssignableToName(clazz, LOG_EXPORTER)) {
            return ClassInfo.Kind.LOG_EXPORTER
        }
        if (isAssignableToName(clazz, OUTPUT)) {
            return ClassInfo.Kind.OUTPUT
        }
        return ClassInfo.Kind.OTHER
    }

    /**
     * A field is a documented property when it is serialized: public, or exposed through a
     * public getter, and not {@code @JsonIgnore}. Internal state fields suppressed with Lombok
     * {@code @Getter(AccessLevel.NONE)} have no getter, so they are excluded.
     */
    private static boolean isProperty(Class<?> owner, Field field) {
        if (find(field, JSON_IGNORE) != null) {
            return false
        }
        if (Modifier.isPublic(field.modifiers)) {
            return true
        }
        return hasPublicAccessor(owner, field.name)
    }

    private static boolean hasPublicAccessor(Class<?> owner, String fieldName) {
        String capitalized = fieldName.length() > 0 ? fieldName[0].toUpperCase() + fieldName.substring(1) : fieldName
        Set<String> candidates = ["get${capitalized}".toString(), "is${capitalized}".toString(), fieldName] as Set
        try {
            return owner.methods.any { it.parameterCount == 0 && candidates.contains(it.name) }
        } catch (Throwable ignored) {
            return false
        }
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
