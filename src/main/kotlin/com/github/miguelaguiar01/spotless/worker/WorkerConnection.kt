package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.protocol.FrameCodec
import com.github.miguelaguiar01.spotless.protocol.Message
import com.github.miguelaguiar01.spotless.protocol.Messages
import com.github.miguelaguiar01.spotless.protocol.ProtocolVersion
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class WorkerDiedException(message: String, cause: Throwable? = null) : IOException(message, cause)
class WorkerProtocolException(message: String) : IOException(message)

/**
 * One live worker process: owns the reader thread, correlates responses to requests, and forwards
 * worker logs to [workerLog]. Thread-safe; [request] is coroutine-friendly and cancellation-aware.
 */
class WorkerConnection(
    private val process: Process,
    private val workerLog: Logger,
    private val onExit: (WorkerConnection) -> Unit,
) {
    private val writer = FrameCodec.Writer(process.outputStream)
    private val reader = FrameCodec.Reader(process.inputStream)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val ids = AtomicLong()
    private val hello = CompletableDeferred<Message.Hello>()

    @Volatile
    var deathReason: String? = null
        private set

    val isAlive: Boolean get() = process.isAlive && deathReason == null
    val pid: Long get() = process.pid()

    init {
        Thread(::readLoop, "spotless-worker-reader-${process.pid()}").apply { isDaemon = true }.start()
        Thread(::drainStderr, "spotless-worker-stderr-${process.pid()}").apply { isDaemon = true }.start()
        process.onExit().thenAccept { p ->
            markDead("worker process exited with code ${p.exitValue()}")
        }
    }

    /** Waits for the worker's hello and checks the protocol version. */
    suspend fun handshake(timeout: Duration): Message.Hello {
        val h = try {
            withTimeout(timeout) { hello.await() }
        } catch (e: TimeoutCancellationException) {
            destroy()
            throw WorkerProtocolException("Worker did not say hello within $timeout")
        }
        if (h.protocolVersion != ProtocolVersion.CURRENT) {
            destroy()
            throw WorkerProtocolException("Worker protocol ${h.protocolVersion} != ${ProtocolVersion.CURRENT}")
        }
        send(Message.Hello(ProtocolVersion.CURRENT, mapOf("role" to "ide")))
        return h
    }

    fun nextId(): String = ids.incrementAndGet().toString()

    /**
     * Sends [build]`(id)` and suspends until the response with the same id arrives. On coroutine
     * cancellation a `cancel` frame is sent for the request. Throws [WorkerDiedException] if the
     * process dies first.
     */
    suspend fun request(timeout: Duration, build: (String) -> Message): Message {
        val id = nextId()
        val deferred = CompletableDeferred<Message>()
        pending[id] = deferred
        try {
            send(build(id))
            return withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            sendQuietly(Message.Cancel(id))
            throw WorkerProtocolException("Worker did not answer request $id within $timeout")
        } catch (e: CancellationException) {
            sendQuietly(Message.Cancel(id))
            throw e
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    fun send(message: Message) {
        deathReason?.let { throw WorkerDiedException(it) }
        try {
            writer.writeFrame(message.encode())
        } catch (e: IOException) {
            markDead("write failed: ${e.message}")
            throw WorkerDiedException("Worker connection broken", e)
        }
    }

    private fun sendQuietly(message: Message) {
        try {
            send(message)
        } catch (_: IOException) {
        }
    }

    /** Asks the worker to exit; falls back to killing it after [graceMs]. */
    fun shutdown(graceMs: Long) {
        try {
            sendQuietly(Message.Shutdown(nextId()))
            if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                workerLog.info("Worker ${process.pid()} ignored shutdown; destroying")
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    fun destroy() {
        process.destroyForcibly()
        markDead("destroyed")
    }

    private fun markDead(reason: String) {
        if (deathReason != null) return
        deathReason = reason
        val ex = WorkerDiedException(reason)
        hello.completeExceptionally(ex)
        pending.values.forEach { it.completeExceptionally(ex) }
        pending.clear()
        onExit(this)
    }

    private fun readLoop() {
        try {
            while (true) {
                val frame = reader.readFrame() ?: break
                val message = try {
                    Messages.decode(frame)
                } catch (e: RuntimeException) {
                    workerLog.warn("Undecodable frame from worker: ${e.message}")
                    continue
                }
                dispatch(message)
            }
            markDead("worker closed the protocol channel")
        } catch (e: IOException) {
            markDead("read failed: ${e.message}")
        }
    }

    private fun dispatch(message: Message) {
        when (message) {
            is Message.Hello -> hello.complete(message)
            is Message.Log -> log(message)
            else -> {
                val id = message.id()
                val waiter = if (id != null) pending.remove(id) else null
                if (waiter != null) {
                    waiter.complete(message)
                } else if (message is Message.Error) {
                    workerLog.warn("Worker error: ${message.message}")
                } else {
                    workerLog.debug("Unsolicited ${message.type()} (id=$id) from worker")
                }
            }
        }
    }

    private fun log(message: Message.Log) {
        val text = if (message.throwable != null) "${message.message}\n${message.throwable}" else message.message
        when (message.level) {
            "ERROR" -> workerLog.warn("[worker] $text") // never pop IDE error dialogs for worker-side failures
            "WARN" -> workerLog.warn("[worker] $text")
            "DEBUG" -> workerLog.debug("[worker] $text")
            else -> workerLog.info("[worker] $text")
        }
    }

    private fun drainStderr() {
        try {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { workerLog.warn("[worker stderr] $it") }
            }
        } catch (_: IOException) {
        }
    }
}
