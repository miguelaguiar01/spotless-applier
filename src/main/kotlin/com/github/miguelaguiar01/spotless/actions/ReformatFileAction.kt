package com.github.miguelaguiar01.spotless.actions

import com.github.miguelaguiar01.spotless.engine.FormatService
import com.github.miguelaguiar01.spotless.imports.SynchronousOptimizeImportsProcessor
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

class ReformatFileAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val available = event.project != null && event.getData(CommonDataKeys.EDITOR) != null &&
            event.getData(CommonDataKeys.PSI_FILE) != null
        event.presentation.isEnabledAndVisible = available
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val settings = SpotlessApplierSettings.getInstance()
        if (settings.optimizeImportsBeforeApplying) {
            SynchronousOptimizeImportsProcessor(project, settings.prohibitImportsWithAsterisk, psiFile).run()
        }
        FormatService.getInstance(project).formatFileInBackground(editor.document, psiFile)
    }
}
