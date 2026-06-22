# gradle-plugins

Gradle plugins that help build Kestra plugins.

- `io.kestra.gradle.spotless-conventions` - applies Spotless with Kestra formatting and git hooks.
- `io.kestra.gradle.develocity-conventions` - configures Develocity build scans and remote build cache.
- `io.kestra.gradle.inject-bom-versions` - injects missing versions for BOM-managed dependencies in the POM.
- `io.kestra.gradle.plugin-doc-lint` - enforces plugin documentation completeness at build time.

## plugin-doc-lint

Checks that a Kestra plugin ships complete documentation: subpackage metadata, icons,
`@Schema` annotations, `@Plugin` examples, property groups, secret annotations, and
`package-info`. It catches gaps at build time instead of code review.

Covers tasks, triggers, task runners, log exporters and output classes. Multi-module
plugins are supported: a submodule may ship a leaf-named metadata file instead of
`index.yaml`.

Apply it:

```groovy
plugins {
    id 'io.kestra.gradle.plugin-doc-lint'
}
```

It registers a `lintPluginDocs` task and wires it into `check`, so `./gradlew check` fails on
any violation. Annotation checks reflect over the compiled classes, so the task depends on
`classes`. Each violation reports a rule id, the class or file path, and a one-line fix.

### Configuration

```groovy
pluginDocLint {
    // skip specific rules by id
    disabledRules = ['DOC-001', 'DOC-003']
    // report violations without failing the build
    ignoreFailures = false
}
```

### Rules

Metadata (`src/main/resources/metadata/`):

| Rule | Check |
|---|---|
| META-001 | `index.yaml` exists for the root package |
| META-002 | every package with a task or trigger has a `<subpackage>.yaml` |
| META-003 | `group`, `name`, `title`, `description` are non-empty and a `body` field is present |
| META-004 | `group` matches the fully-qualified package name |

Icons (`src/main/resources/icons/`):

| Rule | Check |
|---|---|
| ICON-001 | `plugin-icon.svg` exists |
| ICON-002 | each subpackage has `<fully-qualified-subpackage>.svg` |

`@Schema`:

| Rule | Check |
|---|---|
| SCHEMA-001 | every task and trigger class has `@Schema` |
| SCHEMA-002 | the class-level `@Schema` on a task or trigger has a non-empty `description` |
| SCHEMA-003 | every non-static, non-transient field in a task, trigger, task runner or log exporter has `@Schema` |
| SCHEMA-004 | every field in an output class has `@Schema` |
| SCHEMA-005 | no `@Schema` `title` ends with a period (titles inherited from a framework type the plugin cannot edit are exempt) |

`@Plugin` / `@Example`:

| Rule | Check |
|---|---|
| PLUGIN-001 | every task and trigger has `@Plugin(examples = ...)` with at least one example |
| PLUGIN-002 | each `@Example` has `full = true` |
| PLUGIN-003 | each `@Example` is valid YAML with `id:`, `namespace:`, and `tasks:` or `triggers:` |
| PLUGIN-004 | no `@Example` sets `lang = "yaml"` (it is the default) |
| PLUGIN-005 | no example holds a plain-text secret, use `{{ secret('NAME') }}` |

Properties:

| Rule | Check |
|---|---|
| PROP-001 | every `@PluginProperty` group is one of `main`, `connection`, `source`, `processing`, `execution`, `destination`, `reliability`, `advanced`, `deprecated` |
| PROP-002 | fields matching known secret-name patterns (`password`, `apiKey`, `privateKey`, `secret`, `credential`, `apiToken`, `accessToken`, `refreshToken`, ...) use `@PluginProperty(secret = true)`. Matched by concrete credential forms so pagination cursors like `pageToken` are not flagged |
| PROP-003 | no property is named `version` |

Package structure:

| Rule | Check |
|---|---|
| PKG-001 | every package with a task or trigger has a `package-info.java` with `@PluginSubGroup` |
| PKG-002 | the `@PluginSubGroup` category is one of `AI`, `BUSINESS`, `CLOUD`, `CORE`, `DATA`, `INFRASTRUCTURE` |
| PKG-003 | tasks and triggers are not split between the root package and subpackages |

Documentation (`src/main/resources/doc/`):

| Rule | Check |
|---|---|
| DOC-001 | `doc/<root-package>.md` exists |
| DOC-002 | markdown files referenced from `metadata/*.yaml` exist in `doc/` |
| DOC-003 | how-to markdown files have at least 10 lines |

PLUGIN-004 is checked against the source text, because reflection cannot tell an explicit
`lang = "yaml"` from the default. META-003 requires `body` to be present but allows it to be
empty, matching how current plugins ship it.

## Releasing

Releases are tag-based off `main`, using `net.researchgate.release`. To cut a release:

```
git checkout main && git pull
./gradlew release
```

It bumps the version, tags `v<version>`, pushes, and bumps `main` to the next `-SNAPSHOT`. The
pushed tag triggers CI, which publishes all modules to Maven Central and creates the GitHub
release. One release versions all plugins together.

A new patch, minor or major is just the next version number, no release branch needed.
