package com.github.miguelaguiar01.spotless.config.gradle

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.Serializable

/** Persisted in the external-project data cache under each Gradle module node. */
class SpotlessGradleData(
    val pluginVersion: String?,
    val classpath: List<String>,
    val formats: List<Format>,
    val warnings: List<String>,
) : AbstractExternalEntityData(GradleConstants.SYSTEM_ID) {

    class Format(
        val name: String,
        val taskName: String?,
        val stepsBase64: String?,
        val lineEndingPolicyBase64: String?,
        val encoding: String?,
        val targetFiles: List<String>,
        val ratchetFrom: String?,
        val fallbackReason: String?,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        @JvmField
        val KEY: Key<SpotlessGradleData> = Key.create(SpotlessGradleData::class.java, ProjectKeys.MODULE.processingWeight + 1)
    }
}
