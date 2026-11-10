package com.github.miguelaguiar01.spotless.config.gradle

import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.engine.FallbackReason
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import org.jetbrains.plugins.gradle.util.GradleUtil

/** Turns the persisted sync-time model of a Gradle module into a [SpotlessConfig]. */
object GradleSpotlessConfigExtractor {
    /** Tested plugin majors; anything else goes to the build tool (ADR-020). */
    private val SUPPORTED_MAJORS = setOf(7, 8)

    fun extract(module: Module): ConfigLookup {
        val moduleNode: DataNode<ModuleData> = GradleUtil.findGradleModuleData(module)
            ?: return ConfigLookup.Unavailable(FallbackReason.NO_MAVEN_PROJECT, "${module.name} is not a linked Gradle module")
        val data = ExternalSystemApiUtil.find(moduleNode, SpotlessGradleData.KEY)?.data
            ?: return ConfigLookup.Unavailable(FallbackReason.NO_SPOTLESS_PLUGIN_FOUND,
                "${module.name}: no Spotless tasks were found during the last Gradle sync")
        val version = data.pluginVersion
            ?: return ConfigLookup.Unavailable(FallbackReason.EXTRACTION_FAILED, "${module.name}: Spotless Gradle plugin version unknown")
        val major = version.substringBefore('.').toIntOrNull()
        if (major == null || major !in SUPPORTED_MAJORS) {
            return ConfigLookup.Unavailable(FallbackReason.SPOTLESS_VERSION_UNSUPPORTED,
                "com.diffplug.spotless $version is outside the tested range (7.x, 8.x)")
        }
        val warnings = data.warnings.toMutableList()
        val formats = data.formats.map {
            SpotlessConfig.GradleFormat(it.name, it.taskName, it.stepsBase64, it.lineEndingPolicyBase64, it.encoding,
                it.targetFiles, it.ratchetFrom, it.fallbackReason)
        }
        if (formats.all { it.fallbackReason() != null }) {
            return ConfigLookup.Unavailable(FallbackReason.GRADLE_STEP_NOT_SERIALIZABLE,
                formats.joinToString("; ") { "${it.name()}: ${it.fallbackReason()}" })
        }
        val projectDir = moduleNode.data.linkedExternalProjectPath
        val config = SpotlessConfig(
            SpotlessConfig.Kind.GRADLE, version, projectDir, "$projectDir/build", null,
            SpotlessConfig.Gradle(data.classpath, formats), emptyMap(),
        )
        return ConfigLookup.Found(config, warnings)
    }
}
