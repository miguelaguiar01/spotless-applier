package com.github.miguelaguiar01.spotless.config.maven

import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.engine.FallbackReason
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache

/**
 * Turns IntelliJ's imported Maven model into a [SpotlessConfig] without running Maven.
 *
 * Mirrors what `mvn spotless:apply` sees: the plugin-level `<configuration>` merged with the
 * `default-cli` execution (ADR-006). IntelliJ's model is the effective one (inheritance and
 * `pluginManagement` already applied, `${...}` interpolated by Maven's model builder); whatever
 * is still unresolved is interpolated here from the project's properties and built-ins.
 */
object MavenSpotlessConfigExtractor {
    const val GROUP_ID = "com.diffplug.spotless"
    const val ARTIFACT_ID = "spotless-maven-plugin"
    private const val DEFAULT_CLI = "default-cli"

    /** Lowest plugin version we build natively; older ones fall back (ADR-003). */
    val MIN_SUPPORTED = Version(2, 44, 0)

    private val LOG = logger<MavenSpotlessConfigExtractor>()
    private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")

    fun extract(project: Project, file: VirtualFile): ConfigLookup {
        val manager = MavenProjectsManager.getInstance(project)
        if (!manager.isMavenizedProject) {
            return ConfigLookup.Unavailable(FallbackReason.NO_MAVEN_PROJECT, "Not a Maven project")
        }
        val mavenProject = manager.findContainingProject(file)
            ?: return ConfigLookup.Unavailable(FallbackReason.NO_MAVEN_PROJECT, "${file.path} is not inside an imported Maven module")
        return extract(project, mavenProject)
    }

    fun extract(project: Project, mavenProject: MavenProject): ConfigLookup {
        val plugin = mavenProject.findPlugin(GROUP_ID, ARTIFACT_ID)
            ?: return ConfigLookup.Unavailable(
                FallbackReason.NO_SPOTLESS_PLUGIN_FOUND,
                "${mavenProject.displayName}: $GROUP_ID:$ARTIFACT_ID is not declared (directly or via a parent)"
            )
        val version = plugin.version?.takeIf { it.isNotBlank() && !it.contains("\${") }
            ?: return ConfigLookup.Unavailable(
                FallbackReason.EXTRACTION_FAILED,
                "${mavenProject.displayName}: spotless-maven-plugin version is unresolved (${plugin.version})"
            )
        val parsed = Version.parse(version)
        if (parsed == null || parsed < MIN_SUPPORTED) {
            return ConfigLookup.Unavailable(
                FallbackReason.SPOTLESS_VERSION_UNSUPPORTED,
                "spotless-maven-plugin $version is below the supported minimum $MIN_SUPPORTED"
            )
        }

        val warnings = mutableListOf<String>()
        val merged = effectiveConfiguration(plugin)
        val properties = buildProperties(mavenProject, project)
        val xml = interpolate(JDOMUtil.writeElement(merged), properties, warnings)

        val settingsCache = MavenSettingsCache.getInstance(project)
        val manager = MavenProjectsManager.getInstance(project)
        val maven = SpotlessConfig.Maven(
            xml,
            properties,
            mavenProject.sources,
            mavenProject.testSources,
            mavenProject.remotePluginRepositories.map { repo ->
                SpotlessConfig.Repository(
                    repo.id,
                    repo.url,
                    repo.releasesPolicy?.isEnabled ?: true,
                    repo.snapshotsPolicy?.isEnabled ?: false,
                )
            },
            runCatching { settingsCache.getEffectiveUserLocalRepo().toString() }.getOrElse { mavenProject.localRepository.path },
            runCatching { settingsCache.getEffectiveUserSettingsFile().toString() }.getOrNull(),
            runCatching { settingsCache.getEffectiveGlobalSettingsFile()?.toString() }.getOrNull(),
            manager.generalSettings.isWorkOffline,
        )
        val config = SpotlessConfig(
            SpotlessConfig.Kind.MAVEN,
            version,
            mavenProject.directory,
            mavenProject.buildDirectory,
            maven,
            emptyMap(),
        )
        return ConfigLookup.Found(config, warnings)
    }

    /** Plugin-level configuration merged with the `default-cli` execution, as a `<configuration>` element. */
    fun effectiveConfiguration(plugin: MavenPlugin): Element {
        val pluginLevel = plugin.configurationElement
        val cli = plugin.executions.firstOrNull { it.executionId == DEFAULT_CLI }?.configurationElement
        val merged = XmlMerge.merge(cli, pluginLevel) ?: Element("configuration")
        merged.name = "configuration"
        // Detach namespace noise that JDOM may carry from the POM.
        merged.namespace = org.jdom.Namespace.NO_NAMESPACE
        return merged
    }

    private fun buildProperties(mavenProject: MavenProject, project: Project): Map<String, String> {
        val props = linkedMapOf<String, String>()
        // Lowest precedence first; later puts override.
        for (name in System.getProperties().stringPropertyNames()) {
            System.getProperty(name)?.let { props[name] = it }
        }
        for ((k, v) in System.getenv()) props["env.$k"] = v
        for (name in mavenProject.properties.stringPropertyNames()) {
            mavenProject.properties.getProperty(name)?.let { props[name] = it }
        }
        // project.groupId/artifactId/version are interpolated by Maven's model builder already;
        // MavenId lives in a separate bundled plugin (repository-search), so we do not touch it.
        mavenProject.name?.let { props["project.name"] = it }
        props["project.basedir"] = mavenProject.directory
        props["basedir"] = mavenProject.directory
        props["pom.basedir"] = mavenProject.directory
        props["project.build.directory"] = mavenProject.buildDirectory
        props["project.build.outputDirectory"] = mavenProject.outputDirectory
        mavenProject.sources.firstOrNull()?.let { props["project.build.sourceDirectory"] = it }
        mavenProject.testSources.firstOrNull()?.let { props["project.build.testSourceDirectory"] = it }
        runCatching { MavenSettingsCache.getInstance(project).getEffectiveUserLocalRepo().toString() }
            .getOrNull()?.let { props["settings.localRepository"] = it }
        return props
    }

    /** Resolves `${...}` left over by Maven; unknown keys are kept verbatim and reported. */
    fun interpolate(text: String, properties: Map<String, String>, warnings: MutableList<String>): String {
        var current = text
        repeat(10) {
            var changed = false
            current = PLACEHOLDER.replace(current) { m ->
                val key = m.groupValues[1]
                val value = properties[key]
                if (value != null) {
                    changed = true
                    value
                } else {
                    m.value
                }
            }
            if (!changed) {
                PLACEHOLDER.findAll(current).map { it.groupValues[1] }.distinct().forEach { key ->
                    warnings += "Unresolved property \${$key} in spotless configuration"
                }
                return current
            }
        }
        return current
    }

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString() = "$major.$minor.$patch"

        companion object {
            fun parse(text: String): Version? {
                val parts = text.split('.', '-').map { it.toIntOrNull() }
                val major = parts.getOrNull(0) ?: return null
                return Version(major, parts.getOrNull(1) ?: 0, parts.getOrNull(2) ?: 0)
            }
        }
    }
}
