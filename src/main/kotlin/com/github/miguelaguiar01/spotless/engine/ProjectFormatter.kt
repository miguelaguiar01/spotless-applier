package com.github.miguelaguiar01.spotless.engine

import com.github.miguelaguiar01.spotless.buildtool.BuildTool
import com.github.miguelaguiar01.spotless.buildtool.ModuleInfo
import com.github.miguelaguiar01.spotless.config.SpotlessConfigService
import com.github.miguelaguiar01.spotless.notify.SpotlessNotifications
import com.github.miguelaguiar01.spotless.protocol.FormatStatus
import com.github.miguelaguiar01.spotless.protocol.Message
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.github.miguelaguiar01.spotless.settings.Engine
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.github.miguelaguiar01.spotless.worker.WorkerUnavailableException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgress
import com.intellij.util.io.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.relativeTo

/**
 * "Reformat Project/Module With Spotless": streams every candidate file of a Maven module through
 * the worker with bounded parallelism. Open documents are edited in memory (undoable), other files
 * are written to disk with the formatter's line ending, then the VFS is refreshed.
 */
class ProjectFormatter(private val project: Project) {
    private val LOG = logger<ProjectFormatter>()
    private val notifications get() = SpotlessNotifications.getInstance(project)

    data class Summary(val module: String, val scanned: Int, val formatted: Int, val failed: Int, val notCovered: Int,
                       /** Some formats need the build tool; the caller should run it for this module too. */
                       val needsBuildTool: Boolean = false)

    /** Returns null when the module must go through the build tool (reason already notified). */
    suspend fun formatModuleNatively(moduleInfo: ModuleInfo): Summary? {
        if (SpotlessApplierSettings.getInstance().engine == Engine.BUILD_TOOL) return null
        val lookup = if (moduleInfo.buildTool() == BuildTool.MAVEN) {
            val dir = LocalFileSystem.getInstance().findFileByPath(moduleInfo.path()) ?: return null
            val mavenProject = readAction { MavenProjectsManager.getInstance(project).findContainingProject(dir) }
            if (mavenProject == null) {
                notifications.fallback(FallbackReason.NO_MAVEN_PROJECT, "${moduleInfo.path()} is not an imported Maven module")
                return null
            }
            readAction { SpotlessConfigService.getInstance(project).lookup(mavenProject) }
        } else {
            val module = moduleInfo.module() ?: return null
            readAction { SpotlessConfigService.getInstance(project).lookupGradle(module) }
        }
        val config = when (lookup) {
            is ConfigLookup.Unavailable -> {
                notifications.fallback(lookup.reason, lookup.detail)
                return null
            }
            is ConfigLookup.Found -> lookup.config
        }
        val worker = WorkerManager.getInstance(project)
        val loaded = try {
            worker.loadConfig(config)
        } catch (e: WorkerUnavailableException) {
            notifications.fallback(e.reason, e.message ?: e.reason)
            return null
        }
        if (!loaded.isOk) {
            notifications.fallback(loaded.fallbackReason ?: FallbackReason.WORKER_LOAD_FAILED, loaded.error ?: "config load failed")
            return null
        }
        val root = Path.of(config.projectDir)
        val candidates = withContext(Dispatchers.IO) {
            config.gradle?.let { g -> g.formats.filter { it.fallbackReason() == null }.flatMap { it.targetFiles() }.map(Path::of).distinct().sorted() }
                ?: collectCandidates(root, loaded.formats)
        }
        val summary = withBackgroundProgress(project, "Spotless: ${moduleInfo.module()?.name ?: root.fileName}", cancellable = true) {
            formatFiles(config, root, candidates, moduleInfo.module()?.name ?: root.toString())
        }
        if (loaded.fallbackFormats.isNotEmpty()) {
            notifications.fallback(FallbackReason.GRADLE_STEP_NOT_SERIALIZABLE,
                "${summary.module}: formats ${loaded.fallbackFormats.joinToString { it.name }} run through the build tool")
            return summary.copy(needsBuildTool = true)
        }
        return summary
    }

    private suspend fun formatFiles(config: SpotlessConfig, root: Path, files: List<Path>, moduleName: String): Summary {
        val worker = WorkerManager.getInstance(project)
        val semaphore = Semaphore(PARALLELISM)
        var formatted = 0
        var failed = 0
        var notCovered = 0
        val touched = mutableListOf<VirtualFile>()
        reportProgress(files.size) { reporter ->
            coroutineScope {
                files.map { file ->
                    async {
                        semaphore.withPermit {
                            reporter.itemStep(root.relativize(file).toString()) {
                                formatOne(worker, config, file)
                            }
                        }
                    }
                }.awaitAll().forEach { r ->
                    when (r) {
                        is OneResult.Formatted -> { formatted++; r.file?.let { touched += it } }
                        is OneResult.Failed -> { failed++; LOG.warn("Spotless failed on ${r.path}: ${r.message}") }
                        OneResult.NotCovered -> notCovered++
                        OneResult.Clean -> {}
                    }
                }
            }
        }
        if (touched.isNotEmpty()) {
            withContext(Dispatchers.IO) { VfsUtil.markDirtyAndRefresh(false, false, false, *touched.toTypedArray()) }
        }
        val summary = Summary(moduleName, files.size, formatted, failed, notCovered)
        if (failed > 0) {
            notifications.warning("Spotless finished with errors", "$moduleName: $formatted file(s) reformatted, $failed failed (see idea.log)")
        } else {
            notifications.info("Spotless applied", "$moduleName: $formatted of ${files.size - notCovered} covered file(s) reformatted")
        }
        return summary
    }

    private sealed interface OneResult {
        data object Clean : OneResult
        data object NotCovered : OneResult
        data class Formatted(val file: VirtualFile?) : OneResult
        data class Failed(val path: Path, val message: String) : OneResult
    }

    private suspend fun formatOne(worker: WorkerManager, config: SpotlessConfig, file: Path): OneResult {
        val vFile = LocalFileSystem.getInstance().findFileByNioFile(file)
        val document = vFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        val charset = vFile?.charset ?: Charset.defaultCharset()
        val text: String
        val stamp: Long
        if (document != null) {
            val pair = readAction { document.text to document.modificationStamp }
            text = pair.first
            stamp = pair.second
        } else {
            text = withContext(Dispatchers.IO) { Files.readString(file, charset) }.replace("\r\n", "\n").replace('\r', '\n')
            stamp = -1
        }
        val result: Message.FormatResult = try {
            worker.format(config, file.toString(), text)
        } catch (e: WorkerUnavailableException) {
            return OneResult.Failed(file, e.message ?: e.reason)
        }
        return when (result.status) {
            FormatStatus.CLEAN -> OneResult.Clean
            FormatStatus.NOT_COVERED -> OneResult.NotCovered
            FormatStatus.ERROR, FormatStatus.DID_NOT_CONVERGE -> OneResult.Failed(file, result.message ?: result.status.name)
            FormatStatus.FORMATTED -> {
                val newText = result.text ?: return OneResult.Failed(file, "no text")
                if (document != null) {
                    val ok = writeCommandAction(project, FormatService.COMMAND_NAME) {
                        if (document.modificationStamp != stamp) return@writeCommandAction false
                        document.setText(newText)
                        true
                    }
                    if (ok) OneResult.Formatted(vFile) else OneResult.Failed(file, "document changed during formatting")
                } else {
                    val separator = result.lineEnding ?: System.lineSeparator()
                    val bytes = newText.replace("\n", separator).toByteArray(charset)
                    withContext(Dispatchers.IO) { file.write(bytes) }
                    OneResult.Formatted(vFile)
                }
            }
        }
    }

    /** Files under [root] that match any format's includes (Ant patterns ≈ globs); the worker has the final say. */
    private fun collectCandidates(root: Path, formats: List<Message.FormatInfo>): List<Path> {
        val fs = FileSystems.getDefault()
        val matchers: List<Pair<List<PathMatcher>, List<PathMatcher>>> = formats.map { f ->
            f.includes.map { glob(fs, it) } to f.excludes.map { glob(fs, it) }
        }
        val out = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = p.relativeTo(root)
                val relStr = rel.toString()
                if (SKIP_DIRS.any { relStr.startsWith("$it/") || relStr.startsWith("$it\\") }) return@forEach
                val covered = matchers.any { (inc, exc) -> inc.any { it.matches(rel) } && exc.none { it.matches(rel) } }
                if (covered) out.add(p)
            }
        }
        return out.sorted()
    }

    private fun glob(fs: java.nio.file.FileSystem, antPattern: String): PathMatcher {
        var p = antPattern.replace('\\', '/').removePrefix("/")
        if (p.endsWith("/")) p += "**"
        return try {
            fs.getPathMatcher("glob:$p")
        } catch (e: IllegalArgumentException) {
            fs.getPathMatcher("glob:**")
        }
    }

    companion object {
        private const val PARALLELISM = 4
        private val SKIP_DIRS = listOf("target", ".git", ".idea", "node_modules", "build", ".gradle")
    }
}
