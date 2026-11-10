package com.github.miguelaguiar01.spotless.actions

import com.github.miguelaguiar01.spotless.engine.FormatOutcome
import com.github.miguelaguiar01.spotless.engine.FormatService
import com.github.miguelaguiar01.spotless.onsave.SpotlessOnSaveOptions
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Run Spotless" on save. Uses the platform's document-updating hook (cancellable background
 * progress) so the native path edits the document before it is written; the build-tool fallback
 * behaves as in the original plugin (external process rewrites the file after the save).
 */
class ReformatActionOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {

    override val presentableName: String get() = "Run Spotless"

    override fun isEnabledForProject(project: Project): Boolean =
        SpotlessOnSaveOptions.getInstance(project).isRunOnSaveEnabled

    override suspend fun updateDocument(project: Project, document: Document) {
        val options = SpotlessOnSaveOptions.getInstance(project)
        if (!options.isRunOnSaveEnabled) return
        val (psiFile, applies) = readAction {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
            val file = psi?.virtualFile
            val selected = file != null && FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile == file
            psi to (psi != null && selected && options.isFileTypeSelected(psi.fileType))
        }
        if (!applies || psiFile == null) return
        val service = FormatService.getInstance(project)
        val outcome = service.formatDocument(document, FileDocumentManager.getInstance().getFile(document))
        if (outcome is FormatOutcome.FellBack) {
            withContext(Dispatchers.EDT) { service.runBuildTool(document, psiFile) }
        }
    }
}
