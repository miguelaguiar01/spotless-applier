package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.idea.maven.execution.MavenExternalParameters
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.jps.model.java.JdkVersionDetector
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

/** A usable JDK for the worker. */
data class WorkerJdk(val home: Path, val featureVersion: Int, val source: String) {
    val javaExecutable: Path
        get() {
            val bin = home.resolve("bin")
            val win = bin.resolve("java.exe")
            return if (Files.exists(win)) win else bin.resolve("java")
        }
}

class WorkerJdkUnavailableException(message: String) : RuntimeException(message)

/**
 * Picks the JDK the worker runs on. Order: explicit setting, the Maven runner JRE, the Gradle JVM,
 * the project SDK, the IDE's own runtime. The first candidate that exists and is at least
 * [minFeatureVersion] wins. Must not be called on the EDT (version detection runs `java -version`).
 */
object WorkerJdkResolver {
    private val LOG = logger<WorkerJdkResolver>()

    fun resolve(project: Project, minFeatureVersion: Int, target: WorkerTarget = LocalTarget): WorkerJdk {
        val rejected = mutableListOf<String>()
        for ((source, home) in candidates(project, target)) {
            if (home == null) continue
            val path = Path.of(home)
            if (!target.acceptsJdk(path)) {
                rejected += "$source: $home cannot run on the ${target.description}"
                continue
            }
            if (!Files.isDirectory(path)) {
                rejected += "$source: $home does not exist"
                continue
            }
            val version = detectFeatureVersion(path)
            if (version == null) {
                rejected += "$source: cannot detect Java version at $home"
                continue
            }
            if (version < minFeatureVersion) {
                rejected += "$source: Java $version at $home is older than required $minFeatureVersion"
                continue
            }
            LOG.info("Worker JDK: $home (Java $version, from $source)")
            return WorkerJdk(path, version, source)
        }
        throw WorkerJdkUnavailableException(
            "No JDK >= $minFeatureVersion found for the Spotless worker on the ${target.description}. Tried: ${rejected.joinToString("; ")}"
        )
    }

    private fun candidates(project: Project, target: WorkerTarget): List<Pair<String, String?>> {
        val list = mutableListOf<Pair<String, String?>>()
        val override = SpotlessApplierSettings.getInstance().workerJdkHome
        if (override.isNotBlank()) {
            // A Linux path in the settings means "inside the target" for a remote target.
            list += "settings" to if (override.startsWith("/") && target !is LocalTarget) target.toHostPath(override) else override
        }
        list += "Maven runner JRE" to safely { mavenRunnerJdk(project) }
        list += "Gradle JVM" to safely { gradleJvm(project) }
        list += "project SDK" to safely { projectSdk(project) }
        list += safely { target.extraJdkCandidates(project) } ?: emptyList()
        if (target.ideRuntimeIsCandidate) list += "IDE runtime" to System.getProperty("java.home")
        return list
    }

    private fun mavenRunnerJdk(project: Project): String? {
        if (!MavenProjectsManager.getInstance(project).isMavenizedProject) return null
        val jreName = MavenRunner.getInstance(project).state.jreName
        val sdk = runReadActionBlocking { MavenExternalParameters.getJdk(project, jreName, false) }
        return sdk.homePath
    }

    private fun gradleJvm(project: Project): String? {
        val linked = GradleSettings.getInstance(project).linkedProjectsSettings.firstOrNull() ?: return null
        return GradleInstallationManager.getInstance().getGradleJvmPath(project, linked.externalProjectPath)
    }

    private fun projectSdk(project: Project): String? = runReadActionBlocking {
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk?.sdkType is JavaSdkType) return@runReadActionBlocking projectSdk.homePath
        ModuleManager.getInstance(project).modules
            .asSequence()
            .mapNotNull { ModuleRootManager.getInstance(it).sdk }
            .firstOrNull { it.sdkType is JavaSdkType }
            ?.homePath
    }

    private fun detectFeatureVersion(home: Path): Int? =
        safely { JdkVersionDetector.getInstance().detectJdkVersionInfo(home.toString())?.version?.feature }

    private fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        LOG.debug("JDK candidate lookup failed", e)
        null
    }
}
