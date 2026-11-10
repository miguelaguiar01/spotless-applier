package com.github.miguelaguiar01.spotless.config

import com.github.miguelaguiar01.spotless.config.gradle.GradleSpotlessConfigExtractor
import com.github.miguelaguiar01.spotless.config.maven.MavenSpotlessConfigExtractor
import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.engine.FallbackReason
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import org.jetbrains.plugins.gradle.util.GradleConstants
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectChanges
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProjectsTree
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches extracted [ConfigLookup]s per Maven module and invalidates them when IntelliJ re-imports.
 * When a module's config *changes*, the old configId is invalidated in the worker so its Formatters
 * are closed; unchanged configs keep their warm Formatters.
 */
@Service(Service.Level.PROJECT)
class SpotlessConfigService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** Keyed by the Maven module's pom path. */
    private val cache = ConcurrentHashMap<String, ConfigLookup>()

    @Volatile
    private var listening = false

    fun lookup(file: VirtualFile): ConfigLookup {
        ensureListening()
        val manager = MavenProjectsManager.getInstance(project)
        if (manager.isMavenizedProject) {
            val mavenProject = runReadActionBlocking { manager.findContainingProject(file) }
            if (mavenProject != null) return lookup(mavenProject)
        }
        val module = runReadActionBlocking { ModuleUtilCore.findModuleForFile(file, project) }
            ?: return ConfigLookup.Unavailable(FallbackReason.NO_MAVEN_PROJECT, "${file.path} does not belong to a module")
        if (ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) {
            return lookupGradle(module)
        }
        return ConfigLookup.Unavailable(FallbackReason.NO_MAVEN_PROJECT, "${module.name} is neither a Maven nor a Gradle module")
    }

    fun lookupGradle(module: Module): ConfigLookup {
        ensureListening()
        val key = "gradle:" + (ExternalSystemApiUtil.getExternalProjectPath(module) ?: module.name)
        return cache.computeIfAbsent(key) {
            val result = GradleSpotlessConfigExtractor.extract(module)
            if (result is ConfigLookup.Found) {
                LOG.info("Spotless Gradle config for ${module.name}: id=${result.config.configId()} version=${result.config.spotlessVersion}")
                result.warnings.forEach { LOG.warn("${module.name}: $it") }
            } else if (result is ConfigLookup.Unavailable) {
                LOG.info("No native Spotless config for ${module.name}: ${result.reason} (${result.detail})")
            }
            result
        }
    }

    fun lookup(mavenProject: MavenProject): ConfigLookup {
        ensureListening()
        return cache.computeIfAbsent(mavenProject.file.path) {
            val result = MavenSpotlessConfigExtractor.extract(project, mavenProject)
            if (result is ConfigLookup.Found) {
                LOG.info("Spotless config for ${mavenProject.displayName}: id=${result.config.configId()} version=${result.config.spotlessVersion}")
                result.warnings.forEach { LOG.warn("${mavenProject.displayName}: $it") }
            } else if (result is ConfigLookup.Unavailable) {
                LOG.info("No native Spotless config for ${mavenProject.displayName}: ${result.reason} (${result.detail})")
            }
            result
        }
    }

    /** Drops everything; the worker is told to forget every known config. */
    fun invalidateAll() {
        val old = cache.values.filterIsInstance<ConfigLookup.Found>().map { it.config.configId() }
        cache.clear()
        if (old.isNotEmpty()) {
            val worker = WorkerManager.getInstance(project)
            scope.launch(Dispatchers.IO) { old.forEach { worker.invalidate(it) } }
        }
    }

    /** Re-extracts the given modules and invalidates only the configs whose id changed. */
    private fun refresh(mavenProjects: Collection<MavenProject>) {
        if (cache.isEmpty()) return
        val stale = mutableListOf<String>()
        for (mp in mavenProjects) {
            val key = mp.file.path
            val old = cache.remove(key) ?: continue
            val new = lookup(mp)
            val oldId = (old as? ConfigLookup.Found)?.config?.configId()
            val newId = (new as? ConfigLookup.Found)?.config?.configId()
            if (oldId != null && oldId != newId) stale += oldId
        }
        if (stale.isNotEmpty()) {
            LOG.info("Spotless configs changed after Maven import: ${stale.size} invalidated")
            val worker = WorkerManager.getInstance(project)
            scope.launch(Dispatchers.IO) { stale.forEach { worker.invalidate(it) } }
        }
    }

    private fun ensureListening() {
        if (listening) return
        synchronized(this) {
            if (listening) return
            listening = true
            val manager = MavenProjectsManager.getInstance(project)
            manager.addProjectsTreeListener(object : MavenProjectsTree.Listener {
                override fun projectsUpdated(updated: List<Pair<MavenProject, MavenProjectChanges>>, deleted: List<MavenProject>) {
                    deleted.forEach { cache.remove(it.file.path) }
                    refresh(updated.map { it.first })
                }

                override fun projectResolved(projectWithChanges: Pair<MavenProject, MavenProjectChanges>) {
                    refresh(listOf(projectWithChanges.first))
                }

                override fun pluginsResolved(project: MavenProject) {
                    refresh(listOf(project))
                }
            }, this)
            project.messageBus.connect(this).subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun importFinished(importedProjects: Collection<MavenProject>, newModules: List<com.intellij.openapi.module.Module>) {
                    refresh(importedProjects)
                }
            })
            // Gradle: after any sync, drop Gradle entries; the worker's old formatters are invalidated lazily on next use.
            project.messageBus.connect(this).subscribe(ProjectDataImportListener.TOPIC, object : ProjectDataImportListener {
                override fun onImportFinished(projectPath: String?) {
                    val stale = cache.filterKeys { it.startsWith("gradle:") }
                    stale.keys.forEach { cache.remove(it) }
                    val ids = stale.values.filterIsInstance<ConfigLookup.Found>().map { it.config.configId() }
                    if (ids.isNotEmpty()) {
                        val worker = WorkerManager.getInstance(project)
                        scope.launch(Dispatchers.IO) { ids.forEach { worker.invalidate(it) } }
                    }
                }
            })
        }
    }

    override fun dispose() {
        cache.clear()
    }

    companion object {
        private val LOG = logger<SpotlessConfigService>()

        fun getInstance(project: Project): SpotlessConfigService = project.getService(SpotlessConfigService::class.java)
    }
}
