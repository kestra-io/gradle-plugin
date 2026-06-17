package io.kestra.gradle.model

/**
 * Per-package view, sourced from the {@code package-info} class and its
 * {@code @PluginSubGroup} annotation.
 */
class PackageInfo {
    String name
    boolean hasPackageInfo
    boolean hasSubGroup
    List<String> subGroupCategories = []
}
