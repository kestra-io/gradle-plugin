package io.kestra.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.kestra.core.docs.*;
import io.kestra.core.plugins.DefaultPluginRegistry;
import io.kestra.core.plugins.PluginClassAndMetadata;
import io.kestra.core.plugins.RegisteredPlugin;

import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone schema generator that runs at build time in a forked JVM to produce
 * JSON schema files for each plugin class (tasks, triggers, conditions, task runners).
 * <p>
 * Usage: {@code java io.kestra.gradle.DocGenerator <outputDir> [packagePrefix] [generateJsonSchema] [generateMarkdown]}
 */
public class DocGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Configures logging to suppress verbose DEBUG/INFO output from Kestra core internals.
     */
    private static void configureLogging() {
        try {
            // Use Logback API via reflection to avoid a compile-time dependency.
            // The forked JVM classpath includes Logback via the consumer project.
            var loggerContext = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLoggerList().forEach(logger -> logger.setLevel(ch.qos.logback.classic.Level.WARN));
        } catch (Exception e) {
            // Not Logback on the classpath — ignore silently
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DocGenerator <outputDir> [packagePrefix] [generateJsonSchema] [generateMarkdown]");
            System.exit(1);
        }

        configureLogging();

        Path outputDir = Path.of(args[0]);
        String packagePrefix = args.length > 1 ? args[1] : null;
        boolean generateJsonSchema = args.length <= 2 || args[2].equals("true");
        boolean generateMarkdown = args.length <= 3 || args[3].equals("true");

        Files.createDirectories(outputDir.resolve("json-schema"));
        Files.createDirectories(outputDir.resolve("markdown"));

        // Initialize the default registry (scans all plugins on the classpath)
        DefaultPluginRegistry registry = DefaultPluginRegistry.getOrCreate();

        // Create the JSON schema generator
        JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(registry);

        int count = 0;

        for (RegisteredPlugin plugin : registry.plugins()) {
            for (Class pluginClass : plugin.allClass()) {
                if (packagePrefix != null && !pluginClass.getName().startsWith(packagePrefix)) {
                    continue;
                }

                String version = plugin.version() != null ? plugin.version() : "unknown";

                try {
                    Class<?> baseClass = plugin.baseClass(pluginClass.getName());
                    PluginClassAndMetadata<?> metadata = PluginClassAndMetadata.create(
                        plugin,
                        pluginClass,
                        baseClass,
                        null
                    );

                    ClassPluginDocumentation<?> classPluginDocumentation = ClassPluginDocumentation.of(
                        jsonSchemaGenerator,
                        metadata,
                        version,
                        true
                    );

                    Schema schema = new Schema(
                        classPluginDocumentation.getPropertiesSchema(),
                        classPluginDocumentation.getOutputsSchema(),
                        classPluginDocumentation.getDefs()
                    );

                    if (generateJsonSchema) {
                        Path jsonSchemaFilePath = outputDir.resolve("json-schema/" + pluginClass.getName() + ".json");
                        MAPPER.writeValue(jsonSchemaFilePath.toFile(), schema);
                        System.out.println("  Generated json schema: " + jsonSchemaFilePath);
                    }

                    if (generateMarkdown) {
                        var markdown = DocumentationGenerator.render(classPluginDocumentation);
                        Path mardownFilePath = outputDir.resolve("markdown/" + pluginClass.getName() + ".md");
                        Files.writeString(mardownFilePath, markdown);
                        System.out.println("  Generated markdown: " + mardownFilePath);
                    }


                    count++;

                } catch (Exception e) {
                    System.err.println("  WARNING: Failed to generate schema for " + pluginClass.getName() + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }

        System.out.println("Generated " + count + " classes in '" + outputDir + "'" +
            (packagePrefix != null ? " using packagePrefix '" + packagePrefix + "'" : "")
        );
    }
}
