package com.github.miguelaguiar01.spotless.config.gradle

import com.github.miguelaguiar01.spotless.gradle.tooling.SpotlessGradleModel
import com.github.miguelaguiar01.spotless.gradle.tooling.SpotlessModelBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.Order
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension

/**
 * Registers the sync-time model builder with IntelliJ's Gradle import and copies the model into a
 * [SpotlessGradleData] node under the module (ADR-020).
 */
@Order(1000)
class SpotlessGradleProjectResolver : AbstractProjectResolverExtension() {

    override fun getExtraProjectModelClasses(): Set<Class<*>> = setOf(SpotlessGradleModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<*>> = setOf(SpotlessModelBuilder::class.java)

    override fun populateModuleExtraModels(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        try {
            val model = resolverCtx.getExtraProject(gradleModule, SpotlessGradleModel::class.java)
            if (model != null) {
                val data = SpotlessGradleData(
                    model.pluginVersion,
                    model.classpath.toList(),
                    model.formats.map {
                        SpotlessGradleData.Format(
                            it.name, it.taskName, it.stepsBase64, it.lineEndingPolicyBase64, it.encoding,
                            it.targetFiles.toList(), it.ratchetFrom, it.fallbackReason,
                        )
                    },
                    model.warnings.toList(),
                )
                ideModule.createChild(SpotlessGradleData.KEY, data)
                LOG.info("Spotless Gradle model for ${gradleModule.name}: ${data.formats.size} format(s), plugin ${data.pluginVersion}")
            }
        } catch (e: Exception) {
            LOG.warn("Could not read the Spotless Gradle model for ${gradleModule.name}", e)
        }
        super.populateModuleExtraModels(gradleModule, ideModule)
    }

    companion object {
        private val LOG = logger<SpotlessGradleProjectResolver>()
    }
}
