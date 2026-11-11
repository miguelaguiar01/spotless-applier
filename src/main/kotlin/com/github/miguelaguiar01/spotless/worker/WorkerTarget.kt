package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.file.Path

/**
 * Where the worker JVM runs and how paths translate between the IDE and that place (ADR-022).
 * Today: the local machine, or a WSL distribution when the project lives in one.
 */
sealed interface WorkerTarget {
    val description: String

    /** Path separator for the worker's `-cp`. */
    val classpathSeparator: String

    /** IDE-side path → path as the worker sees it. */
    fun toWorkerPath(hostPath: String): String

    /** Worker-side path → IDE-side path (system-independent, forward slashes). */
    fun toHostPath(workerPath: String): String

    /** Config with every path translated for the worker. */
    fun mapConfig(config: SpotlessConfig): SpotlessConfig

    /** Whether a JDK installed at this IDE-side path can run on this target. */
    fun acceptsJdk(home: Path): Boolean

    /** Extra JDK candidates (IDE-side paths) specific to this target, after the usual ones. */
    fun extraJdkCandidates(): List<Pair<String, String?>>

    /** Whether the IDE's own runtime is a candidate (never for a remote target). */
    val ideRuntimeIsCandidate: Boolean

    /** Executable to start the worker with, as the target sees it. */
    fun javaExecutable(jdk: WorkerJdk): String

    /** Applies the working directory and wraps the command for the target. */
    fun finish(command: GeneralCommandLine, workingDirectory: Path, project: Project): GeneralCommandLine

    companion object {
        private val LOG = logger<WorkerTarget>()

        fun forProject(project: Project): WorkerTarget = forPath(project.basePath)

        fun forPath(path: String?): WorkerTarget {
            if (path == null || !SystemInfo.isWindows) return LocalTarget
            val distro = WslPathMapper.distributionIdOf(path) ?: return LocalTarget
            return try {
                val distribution = WslDistributionManager.getInstance().getOrCreateDistributionByMsId(distro)
                WslTarget(distribution, WslPathMapper(distro))
            } catch (e: Exception) {
                LOG.warn("Cannot resolve WSL distribution '$distro' for $path; using the local worker", e)
                LocalTarget
            }
        }
    }
}

object LocalTarget : WorkerTarget {
    override val description = "local machine"
    override val classpathSeparator: String = File.pathSeparator
    override fun toWorkerPath(hostPath: String) = hostPath
    override fun toHostPath(workerPath: String) = workerPath
    override fun mapConfig(config: SpotlessConfig) = config
    override fun acceptsJdk(home: Path) = true
    override fun extraJdkCandidates(): List<Pair<String, String?>> = emptyList()
    override val ideRuntimeIsCandidate = true
    override fun javaExecutable(jdk: WorkerJdk): String = jdk.javaExecutable.toString()
    override fun finish(command: GeneralCommandLine, workingDirectory: Path, project: Project): GeneralCommandLine =
        command.withWorkDirectory(workingDirectory.toFile())
}

/**
 * The project lives in a WSL distribution: the worker runs inside it via `wsl.exe`, reads the
 * plugin's jars through `/mnt/<drive>/...`, and gets Linux paths for everything.
 */
class WslTarget(val distribution: WSLDistribution, private val mapper: WslPathMapper) : WorkerTarget {
    override val description = "WSL distribution '${distribution.msId}'"
    override val classpathSeparator = ":"

    override fun toWorkerPath(hostPath: String): String {
        if (mapper.isWslPath(hostPath)) return mapper.toLinuxPath(hostPath)
        if (hostPath.startsWith("/")) return hostPath // already a Linux path (e.g. Gradle model data)
        return runCatching { distribution.getWslPath(Path.of(hostPath)) }.getOrNull() ?: hostPath
    }

    override fun toHostPath(workerPath: String): String {
        if (!workerPath.startsWith("/")) return workerPath
        val windows = runCatching { distribution.getWindowsPath(workerPath) }.getOrNull()
            ?: "//wsl.localhost/${distribution.msId}$workerPath"
        return FileUtil.toSystemIndependentName(windows)
    }

    override fun mapConfig(config: SpotlessConfig): SpotlessConfig =
        config.mapPaths({ toWorkerPath(it) }, { mapper.toLinuxInText(it) })

    override fun acceptsJdk(home: Path): Boolean = mapper.isWslPath(home.toString())

    override fun extraJdkCandidates(): List<Pair<String, String?>> {
        val javaHome = runCatching { distribution.getEnvironmentVariable("JAVA_HOME") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return listOf("JAVA_HOME in ${distribution.msId}" to javaHome?.let { toHostPath(it) })
    }

    override val ideRuntimeIsCandidate = false

    override fun javaExecutable(jdk: WorkerJdk): String = toWorkerPath(jdk.home.toString()).trimEnd('/') + "/bin/java"

    override fun finish(command: GeneralCommandLine, workingDirectory: Path, project: Project): GeneralCommandLine {
        // No Windows working directory: wsl.exe cannot be started in a UNC directory.
        command.withWorkDirectory(null as File?)
        val options = WSLCommandLineOptions()
            .setLaunchWithWslExe(true)
            .setRemoteWorkingDirectory(toWorkerPath(workingDirectory.toString()))
        return distribution.patchCommandLine(command, project, options)
    }
}
