package com.github.miguelaguiar01.spotless.format

import com.github.miguelaguiar01.spotless.config.SpotlessConfigService
import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.notify.SpotlessNotifications
import com.github.miguelaguiar01.spotless.protocol.FormatStatus
import com.github.miguelaguiar01.spotless.settings.Engine
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import kotlinx.coroutines.runBlocking

/**
 * Opt-in: lets IntelliJ's own "Reformat Code" delegate to Spotless for files covered by the module's
 * Spotless configuration. Whole-file only; range formatting falls through to the IDE formatter.
 */
class SpotlessFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean {
        if (!SpotlessApplierSettings.getInstance().integrateWithReformatCode) return false
        if (SpotlessApplierSettings.getInstance().engine != Engine.NATIVE) return false
        val vFile = file.virtualFile ?: return false
        if (!vFile.isInLocalFileSystem) return false
        return SpotlessConfigService.getInstance(file.project).lookup(vFile) is ConfigLookup.Found
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val project = context.project
        val vFile = context.virtualFile ?: return null
        val lookup = SpotlessConfigService.getInstance(project).lookup(vFile) as? ConfigLookup.Found ?: return null
        return object : FormattingTask {
            @Volatile
            private var cancelled = false

            override fun run() {
                try {
                    val result = runBlocking { WorkerManager.getInstance(project).format(lookup.config, vFile.path, request.documentText) }
                    if (cancelled) return
                    when (result.status) {
                        FormatStatus.FORMATTED -> request.onTextReady(result.text)
                        FormatStatus.CLEAN, FormatStatus.NOT_COVERED -> request.onTextReady(request.documentText)
                        FormatStatus.ERROR, FormatStatus.DID_NOT_CONVERGE -> request.onError("Spotless", result.message ?: result.status.name)
                    }
                } catch (e: Exception) {
                    LOG.warn("Spotless formatting via Reformat Code failed", e)
                    SpotlessNotifications.getInstance(project).fallback(
                        (e as? com.github.miguelaguiar01.spotless.worker.WorkerUnavailableException)?.reason ?: "WORKER_UNAVAILABLE",
                        e.message ?: "worker unavailable",
                    )
                    request.onError("Spotless", e.message ?: "worker unavailable")
                }
            }

            override fun cancel(): Boolean {
                cancelled = true
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }

    override fun getNotificationGroupId(): String = SpotlessNotifications.GROUP_ID

    override fun getName(): String = "Spotless"

    companion object {
        private val LOG = logger<SpotlessFormattingService>()
    }
}
