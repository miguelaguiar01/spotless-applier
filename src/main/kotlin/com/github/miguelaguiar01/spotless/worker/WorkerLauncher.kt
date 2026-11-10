package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/** Builds the command line that starts a worker JVM. */
object WorkerLauncher {
    const val PLUGIN_ID = "com.github.miguelaguiar01.spotless-applier-native"
    const val MAIN_CLASS = "com.github.miguelaguiar01.spotless.worker.WorkerMain"

    /** Override for tests and `runIde` experiments. */
    const val WORKER_DIR_PROPERTY = "spotless.applier.worker.dir"

    /**
     * Flags Spotless needs on JDK 16+: google-java-format, palantir and cleanthat use javac
     * internals. Spotless opens them reflectively, which needs `java.lang` opened; we also add the
     * exports explicitly so it keeps working where the reflective route is blocked (ADR-014).
     */
    val JVM_FLAGS: List<String> = listOf(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
        "-XX:+UseSerialGC",
        "-Xshare:auto",
    )

    /** Resolved-classpath cache, under the IDE system directory. */
    fun cacheDirectory(): Path = Path.of(PathManager.getSystemPath(), "spotless-applier-native")

    /**
     * `<plugin>/lib/worker`, located relative to this class's own jar (`<plugin>/lib/<plugin>.jar`).
     * Deliberately avoids the plugin registry: `PluginManagerCore.getPlugin` and
     * `PluginManager.findEnabledPlugin` are `@ApiStatus.Internal` on 2026.2+.
     */
    fun workerDirectory(): Path {
        System.getProperty(WORKER_DIR_PROPERTY)?.let { return Path.of(it) }
        val jar = PathManager.getJarPathForClass(WorkerLauncher::class.java)
            ?: throw IllegalStateException("Cannot locate the plugin jar of $PLUGIN_ID")
        val libDir = Path.of(jar).toAbsolutePath().parent
            ?: throw IllegalStateException("Plugin jar $jar has no parent directory")
        return libDir.resolve("worker")
    }

    fun workerClasspath(dir: Path = workerDirectory()): List<Path> {
        if (!Files.isDirectory(dir)) throw IllegalStateException("Worker directory $dir is missing")
        val jars = Files.list(dir).use { s -> s.asSequence().filter { it.isRegularFile() && it.name.endsWith(".jar") }.sorted().toList() }
        if (jars.isEmpty()) throw IllegalStateException("Worker directory $dir contains no jars")
        return jars
    }

    fun commandLine(jdk: WorkerJdk, workingDirectory: Path, settings: SpotlessApplierSettings): GeneralCommandLine {
        val idleMs = settings.workerIdleTimeoutMinutes.coerceAtLeast(0) * 60_000L
        return GeneralCommandLine()
            .withExePath(jdk.javaExecutable.toString())
            .withWorkDirectory(workingDirectory.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(JVM_FLAGS)
            .withParameters("-Xmx${settings.workerMaxHeapMb.coerceAtLeast(256)}m")
            .withParameters("-cp", workerClasspath().joinToString(File.pathSeparator))
            .withParameters(
                MAIN_CLASS,
                "--idle-timeout-ms=$idleMs",
                "--log-level=INFO",
                "--cache-dir=${cacheDirectory()}",
            )
            .also { cmd ->
                // Keep user JVM options from hijacking stdout. Removing (not blanking) the variable also
                // avoids the JVM's "Picked up JAVA_TOOL_OPTIONS:" banner on stderr.
                cmd.environment.remove("JAVA_TOOL_OPTIONS")
                cmd.environment.remove("_JAVA_OPTIONS")
            }
    }
}
