package com.github.miguelaguiar01.spotless.startup

import com.github.miguelaguiar01.spotless.config.SpotlessConfigService
import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.onsave.SpotlessOnSaveOptions
import com.github.miguelaguiar01.spotless.settings.Engine
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Loads the on-save options off the EDT (see the original plugin's ijent fix) and, when enabled,
 * pre-warms the worker with every Maven module's Spotless config.
 */
internal class SpotlessStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        readAction { SpotlessOnSaveOptions.getInstance(project) }

        val settings = SpotlessApplierSettings.getInstance()
        if (!settings.prewarmOnProjectOpen || settings.engine != Engine.NATIVE) return
        val manager = MavenProjectsManager.getInstance(project)
        if (!manager.isMavenizedProject) return
        val configService = SpotlessConfigService.getInstance(project)
        val worker = WorkerManager.getInstance(project)
        for (mavenProject in manager.projects) {
            val lookup = readAction { configService.lookup(mavenProject) }
            if (lookup is ConfigLookup.Found) {
                try {
                    val loaded = worker.loadConfig(lookup.config)
                    LOG.info("Pre-warmed ${mavenProject.displayName}: ok=${loaded.isOk} ${loaded.error ?: ""}")
                } catch (e: Exception) {
                    LOG.info("Pre-warm failed for ${mavenProject.displayName}: ${e.message}")
                    return
                }
            }
        }
    }

    companion object {
        private val LOG = logger<SpotlessStartupActivity>()
    }
}
