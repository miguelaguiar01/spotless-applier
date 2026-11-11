package com.github.miguelaguiar01.spotless.engine

import com.github.miguelaguiar01.spotless.buildtool.ModuleInfo
import com.github.miguelaguiar01.spotless.buildtool.ReformatProcessor
import com.github.miguelaguiar01.spotless.config.SpotlessConfigService
import com.github.miguelaguiar01.spotless.notify.SpotlessNotifications
import com.github.miguelaguiar01.spotless.protocol.FormatStatus
import com.github.miguelaguiar01.spotless.protocol.Message
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.github.miguelaguiar01.spotless.settings.Engine
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.github.miguelaguiar01.spotless.worker.WslPathMapper
import com.github.miguelaguiar01.spotless.worker.WorkerUnavailableException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** What happened to one document. */
sealed interface FormatOutcome {
    data object Clean : FormatOutcome
    data class Formatted(val changed: Boolean) : FormatOutcome
    data object NotCovered : FormatOutcome
    data class Failed(val message: String) : FormatOutcome
    data class FellBack(val reason: String, val detail: String) : FormatOutcome
    data object DocumentChanged : FormatOutcome
}

/**
 * Formats in-memory documents: resolves the module's Spotless config, asks the worker off the EDT,
 * applies the result as one undoable write command with a minimal replacement, and routes every
 * "can't do it natively" case to the build-tool engine with a visible reason.
 */
@Service(Service.Level.PROJECT)
class FormatService(private val project: Project, private val scope: CoroutineScope) {

    private val notifications get() = SpotlessNotifications.getInstance(project)

    /** Entry point for actions: launches the whole flow in the background with progress. */
    fun formatFileInBackground(document: Document, psiFile: PsiFile) {
        scope.launch {
            withBackgroundProgress(project, "Reformatting with Spotless", cancellable = true) {
                val outcome = formatDocument(document, psiFile.virtualFile)
                if (outcome is FormatOutcome.FellBack) {
                    withContext(Dispatchers.EDT) { runBuildTool(document, psiFile) }
                }
            }
        }
    }

    /** Native path; never runs the build tool itself. */
    suspend fun formatDocument(document: Document, file: VirtualFile?): FormatOutcome {
        if (file == null || !file.isInLocalFileSystem) {
            return FormatOutcome.FellBack(FallbackReason.NO_MAVEN_PROJECT, "File is not on the local file system")
        }
        if (SpotlessApplierSettings.getInstance().engine == Engine.BUILD_TOOL) {
            return FormatOutcome.FellBack(FallbackReason.USER_CHOICE, "Engine set to Build tool in settings")
        }
        if (!isLocal(file)) {
            return FormatOutcome.FellBack(FallbackReason.NOT_IMPLEMENTED, "Remote (Dev Container) projects use the build tool in this version")
        }
        val lookup = readAction { SpotlessConfigService.getInstance(project).lookup(file) }
        return when (lookup) {
            is ConfigLookup.Unavailable -> FormatOutcome.FellBack(lookup.reason, lookup.detail)
            is ConfigLookup.Found -> formatDocumentWith(lookup.config, document, file.path, file.name)
        }
    }

    /** Formats [document] as if it were the file at [path] under [config]; applies the result. */
    suspend fun formatDocumentWith(config: SpotlessConfig, document: Document, path: String, displayName: String): FormatOutcome {
        val (text, stamp) = readAction { document.text to document.modificationStamp }
        val start = TimeSource.Monotonic.markNow()
        val result = try {
            WorkerManager.getInstance(project).format(config, path, text)
        } catch (e: WorkerUnavailableException) {
            LOG.warn("Native formatting unavailable for $path: ${e.message}")
            return FormatOutcome.FellBack(e.reason, e.message ?: e.reason)
        }
        val elapsed = start.elapsedNow()
        reportLints(displayName, result)
        return when (result.status) {
            FormatStatus.CLEAN -> {
                notifications.statusBar("Spotless: $displayName is already clean (${elapsed.inWholeMilliseconds} ms)")
                FormatOutcome.Clean
            }
            FormatStatus.NOT_COVERED -> {
                val manager = WorkerManager.getInstance(project)
                if (manager.needsBuildTool(config, path)) {
                    val names = manager.loadedInfo(config)?.fallbackFormats?.joinToString { it.name } ?: "?"
                    FormatOutcome.FellBack(FallbackReason.GRADLE_STEP_NOT_SERIALIZABLE,
                        "$displayName belongs to a Spotless format the native engine cannot run ($names)")
                } else {
                    notifications.statusBar("Spotless: $displayName is not covered by the Spotless configuration")
                    FormatOutcome.NotCovered
                }
            }
            FormatStatus.DID_NOT_CONVERGE -> {
                notifications.error("Spotless did not converge", "$displayName: ${result.message}")
                FormatOutcome.Failed(result.message ?: "did not converge")
            }
            FormatStatus.ERROR -> {
                notifications.error("Spotless failed", "$displayName: ${result.message}")
                FormatOutcome.Failed(result.message ?: "error")
            }
            FormatStatus.FORMATTED -> {
                val newText = result.text ?: return FormatOutcome.Failed("worker returned no text")
                val applied = applyText(document, text, stamp, newText)
                if (applied) {
                    notifications.statusBar("Spotless: reformatted $displayName (${elapsed.inWholeMilliseconds} ms)")
                    FormatOutcome.Formatted(changed = true)
                } else {
                    notifications.statusBar("Spotless: $displayName changed while formatting; not applied")
                    FormatOutcome.DocumentChanged
                }
            }
        }
    }

    /** Replaces only the differing middle range so carets/folds outside it survive. One undo step. */
    private suspend fun applyText(document: Document, oldText: String, stamp: Long, newText: String): Boolean {
        return writeCommandAction(project, COMMAND_NAME) {
            if (document.modificationStamp != stamp || document.text != oldText) return@writeCommandAction false
            if (!document.isWritable) return@writeCommandAction false
            val prefix = commonPrefix(oldText, newText)
            val suffix = commonSuffix(oldText, newText, prefix)
            document.replaceString(prefix, oldText.length - suffix, newText.substring(prefix, newText.length - suffix))
            PsiDocumentManager.getInstance(project).commitDocument(document)
            true
        }
    }

    private fun reportLints(displayName: String, result: Message.FormatResult) {
        if (result.lints.isEmpty()) return
        val body = result.lints.joinToString("<br>") { "${it.step} line ${it.line}: ${it.message}" }
        notifications.error("Spotless lints in $displayName", body)
    }

    /** Build-tool engine (original behavior). Must be called on the EDT. */
    fun runBuildTool(document: Document, psiFile: PsiFile) {
        ReformatProcessor(project, document, psiFile).run()
    }

    fun runBuildTool(moduleInfo: ModuleInfo) {
        ReformatProcessor(project, moduleInfo).run()
    }

    private fun isLocal(file: VirtualFile): Boolean {
        // Files inside a WSL distribution are handled by WslTarget (the worker runs in the distribution).
        if (WslPathMapper.distributionIdOf(file.path) != null) return true
        val nio = runCatching { file.toNioPath() }.getOrNull() ?: return true
        val descriptor = runCatching { nio.getEelDescriptor() }.getOrNull() ?: return true
        return descriptor == LocalEelDescriptor
    }

    companion object {
        const val COMMAND_NAME = "Reformat with Spotless"
        private val LOG = logger<FormatService>()

        fun getInstance(project: Project): FormatService = project.getService(FormatService::class.java)

        fun commonPrefix(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        fun commonSuffix(a: String, b: String, prefix: Int): Int {
            val max = minOf(a.length, b.length) - prefix
            var i = 0
            while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
            return i
        }

        /** The Document for a file, loading it if necessary; null for binary/too-large files. */
        fun documentOf(file: VirtualFile): Document? = FileDocumentManager.getInstance().getDocument(file)
    }
}
