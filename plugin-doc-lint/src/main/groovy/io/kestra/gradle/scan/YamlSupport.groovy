package io.kestra.gradle.scan

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/**
 * Thin wrapper over SnakeYAML used by both the resource scanner and the example rules.
 */
class YamlSupport {

    /** Parse a YAML file into a Map, or null when it is invalid or not a mapping. */
    static Map<String, Object> parseMap(File file) {
        if (file == null || !file.exists()) {
            return null
        }
        return parseMap(file.getText('UTF-8'))
    }

    /** Parse a YAML string into a Map, or null when it is invalid or not a mapping. */
    static Map<String, Object> parseMap(String text) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text)
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded
            }
            return null
        } catch (YAMLException ignored) {
            return null
        }
    }

    private YamlSupport() {}
}
