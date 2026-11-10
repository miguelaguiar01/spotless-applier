package com.github.miguelaguiar01.spotless.actions

import com.github.miguelaguiar01.spotless.buildtool.ModuleInfo
import com.github.miguelaguiar01.spotless.engine.FormatService
import com.github.miguelaguiar01.spotless.engine.ProjectFormatter
import com.github.miguelaguiar01.spotless.imports.SynchronousOptimizeImportsProcessor
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.github.miguelaguiar01.spotless.ui.SelectModuleDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.ModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Module selection as in the original plugin; each module is formatted natively or via the build tool. */
class ReformatProjectAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val available: Map<String, ModuleInfo> = ModuleManager.getInstance(project).modules
            .mapNotNull { ModuleInfo.create(project, it) }
            .associateBy { it.module().name }
        if (available.isEmpty()) return

        val selected: List<ModuleInfo> = if (available.size == 1) {
            available.values.toList()
        } else {
            val rootModule = available.values.firstOrNull { it.rootModule() }
            val dialog = SelectModuleDialog(project, available.filterValues { !it.rootModule() }.keys.toList(), rootModule != null)
            if (!dialog.showAndGet()) return
            dialog.saveModuleSettings()
            if (rootModule != null && dialog.applyOnRootProjectCheckbox) listOf(rootModule)
            else dialog.selectedModules.mapNotNull { available[it] }
        }

        val settings = SpotlessApplierSettings.getInstance()
        if (settings.optimizeImportsBeforeApplying) {
            selected.forEach { SynchronousOptimizeImportsProcessor(project, settings.prohibitImportsWithAsterisk, it.module()).run() }
        }
        project.service<ReformatProjectScope>().scope.launch {
            val formatter = ProjectFormatter(project)
            for (moduleInfo in selected) {
                val summary = formatter.formatModuleNatively(moduleInfo)
                if (summary == null) {
                    withContext(Dispatchers.EDT) { FormatService.getInstance(project).runBuildTool(moduleInfo) }
                }
            }
        }
    }
}

@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class ReformatProjectScope(@Suppress("unused") private val project: Project, val scope: CoroutineScope)
