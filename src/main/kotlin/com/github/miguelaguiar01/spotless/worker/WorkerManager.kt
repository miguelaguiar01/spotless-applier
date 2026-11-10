package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.protocol.Message
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The worker cannot be used right now; carries a stable reason code for the fallback. */
class WorkerUnavailableException(val reason: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Project-level owner of the worker process: lazy start, handshake, restart with backoff, config
 * (re)loading after restarts, graceful shutdown on project close.
 */
@Service(Service.Level.PROJECT)
class WorkerManager(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val mutex = Mutex()

    @Volatile
    private var connection: WorkerConnection? = null

    /** Configs loaded into the *current* connection. */
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    /** Known configs, re-sent after a restart. */
    private val configs = ConcurrentHashMap<String, SpotlessConfig>()

    private var consecutiveFailures = 0
    private var lastFailure: TimeSource.Monotonic.ValueTimeMark? = null

    val isRunning: Boolean get() = connection?.isAlive == true

    /** Minimum Java feature version the worker needs for the given Spotless config. */
    fun requiredJava(config: SpotlessConfig?): Int {
        val v = config?.spotlessVersion ?: return 17
        val major = v.substringBefore('.').toIntOrNull() ?: return 17
        return when (config.kind) {
            SpotlessConfig.Kind.MAVEN -> if (major >= 3) 17 else 11
            SpotlessConfig.Kind.GRADLE -> if (major >= 8) 17 else 11
            SpotlessConfig.Kind.BUILTIN_TEST -> 17
        }
    }

    suspend fun ping(): Duration {
        val conn = connected(null)
        val start = TimeSource.Monotonic.markNow()
        val reply = conn.request(REQUEST_TIMEOUT) { Message.Ping(it) }
        check(reply is Message.Pong) { "Expected pong, got ${reply.type()}" }
        return start.elapsedNow()
    }

    suspend fun loadConfig(config: SpotlessConfig): Message.ConfigLoaded {
        val conn = connected(config)
        return load(conn, config)
    }

    /** Formats [text] under [config], loading the config first if this worker has not seen it. */
    suspend fun format(config: SpotlessConfig, path: String, text: String): Message.FormatResult {
        val conn = connected(config)
        val configId = config.configId()
        if (!loaded.contains(configId)) {
            val result = load(conn, config)
            if (!result.isOk) {
                throw WorkerUnavailableException(result.fallbackReason ?: "WORKER_LOAD_FAILED", result.error ?: "config load failed")
            }
        }
        val reply = conn.request(FORMAT_TIMEOUT) { Message.Format(it, configId, path, text) }
        check(reply is Message.FormatResult) { "Expected formatResult, got ${reply.type()}" }
        return reply
    }

    suspend fun invalidate(configId: String) {
        configs.remove(configId)
        loaded.remove(configId)
        val conn = connection?.takeIf { it.isAlive } ?: return
        try {
            conn.request(REQUEST_TIMEOUT) { Message.Invalidate(it, configId) }
        } catch (e: Exception) {
            LOG.debug("invalidate($configId) failed", e)
        }
    }

    fun invalidateAll() {
        configs.clear()
        loaded.clear()
        connection?.takeIf { it.isAlive }?.let { conn ->
            connection = null
            scope.launch(Dispatchers.IO) {
                try {
                    conn.shutdown(SHUTDOWN_GRACE_MS)
                } catch (e: Exception) {
                    LOG.debug(e)
                }
            }
        }
    }

    /** Stops the worker. Configs are remembered and re-sent on the next start. */
    fun shutdown() {
        val conn = connection ?: return
        connection = null
        loaded.clear()
        conn.shutdown(SHUTDOWN_GRACE_MS)
    }

    override fun dispose() {
        shutdown()
    }

    private suspend fun load(conn: WorkerConnection, config: SpotlessConfig): Message.ConfigLoaded {
        val configId = config.configId()
        configs[configId] = config
        val reply = conn.request(LOAD_TIMEOUT) { Message.LoadConfig(it, config) }
        check(reply is Message.ConfigLoaded) { "Expected configLoaded, got ${reply.type()}" }
        if (reply.isOk) loaded.add(configId)
        reply.warnings.forEach { WORKER_LOG.warn("[$configId] $it") }
        return reply
    }

    private suspend fun connected(config: SpotlessConfig?): WorkerConnection {
        connection?.takeIf { it.isAlive }?.let { return it }
        return mutex.withLock {
            connection?.takeIf { it.isAlive }?.let { return it }
            lastFailure?.let { mark ->
                if (consecutiveFailures >= MAX_FAILURES && mark.elapsedNow() < FAILURE_COOLDOWN) {
                    throw WorkerUnavailableException(
                        "WORKER_UNAVAILABLE",
                        "Spotless worker failed $consecutiveFailures times in a row; retry in ${(FAILURE_COOLDOWN - mark.elapsedNow()).inWholeSeconds}s"
                    )
                }
            }
            try {
                val conn = start(requiredJava(config))
                consecutiveFailures = 0
                lastFailure = null
                connection = conn
                loaded.clear()
                conn
            } catch (e: WorkerUnavailableException) {
                recordFailure()
                throw e
            } catch (e: Exception) {
                recordFailure()
                throw WorkerUnavailableException("WORKER_UNAVAILABLE", "Spotless worker failed to start: ${e.message}", e)
            }
        }
    }

    private fun recordFailure() {
        consecutiveFailures++
        lastFailure = TimeSource.Monotonic.markNow()
    }

    private suspend fun start(minJava: Int): WorkerConnection = withContext(Dispatchers.IO) {
        val settings = SpotlessApplierSettings.getInstance()
        val jdk = try {
            WorkerJdkResolver.resolve(project, minJava)
        } catch (e: WorkerJdkUnavailableException) {
            throw WorkerUnavailableException("NO_SUITABLE_JDK", e.message ?: "no JDK", e)
        }
        val workDir = project.basePath?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
            ?: Path.of(System.getProperty("user.home"))
        val commandLine = WorkerLauncher.commandLine(jdk, workDir, settings)
        LOG.info("Starting Spotless worker: ${commandLine.commandLineString}")
        val process = commandLine.createProcess()
        val conn = WorkerConnection(process, WORKER_LOG) { dead ->
            LOG.info("Spotless worker ${dead.pid} is gone: ${dead.deathReason}")
            if (connection === dead) {
                connection = null
                loaded.clear()
            }
        }
        val hello = conn.handshake(HANDSHAKE_TIMEOUT)
        LOG.info("Spotless worker pid=${conn.pid} ready: ${hello.info}")
        conn
    }

    companion object {
        private val LOG = logger<WorkerManager>()

        /** Dedicated category so users can enable it via Help | Diagnostic Tools | Debug Log Settings. */
        val WORKER_LOG: Logger = Logger.getInstance("#com.github.miguelaguiar01.spotless.worker")

        private val HANDSHAKE_TIMEOUT = 30.seconds
        private val REQUEST_TIMEOUT = 15.seconds
        private val LOAD_TIMEOUT = 10.minutes // first load may download artifacts
        private val FORMAT_TIMEOUT = 2.minutes
        private const val SHUTDOWN_GRACE_MS = 3_000L
        private const val MAX_FAILURES = 3
        private val FAILURE_COOLDOWN = 1.minutes

        fun getInstance(project: Project): WorkerManager = project.getService(WorkerManager::class.java)
    }
}
