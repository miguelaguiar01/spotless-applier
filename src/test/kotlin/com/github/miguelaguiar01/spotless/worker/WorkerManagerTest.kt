package com.github.miguelaguiar01.spotless.worker

import com.github.miguelaguiar01.spotless.protocol.FormatStatus
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/** Starts a real worker from the IDE side (worker jars come from the `spotless.applier.worker.dir` property). */
class WorkerManagerTest : BasePlatformTestCase() {

    private fun config(extra: Map<String, Any>) =
        SpotlessConfig(SpotlessConfig.Kind.BUILTIN_TEST, null, project.basePath ?: "/tmp", null, null, extra)

    fun testPingStartsWorker() = runBlocking {
        val manager = WorkerManager.getInstance(project)
        assertFalse(manager.isRunning)
        val rtt = manager.ping()
        assertTrue(manager.isRunning)
        assertTrue("round trip $rtt", rtt.inWholeSeconds < 5)
    }

    fun testFormatLoadsConfigLazilyAndSurvivesRestart() = runBlocking {
        val manager = WorkerManager.getInstance(project)
        val cfg = config(mapOf("steps" to "trimTrailingWhitespace,endWithNewline", "misbehave" to "closeStdout"))
        val r1 = manager.format(cfg, "/tmp/A.java", "a  \nb ")
        assertEquals(FormatStatus.FORMATTED, r1.status)
        assertEquals("a\nb\n", r1.text)

        manager.shutdown()
        assertFalse(manager.isRunning)

        val r2 = manager.format(cfg, "/tmp/A.java", "a\nb\n")
        assertEquals(FormatStatus.CLEAN, r2.status)
        assertTrue(manager.isRunning)
    }

    fun testIncompleteConfigReportsFallbackReason() = runBlocking {
        val manager = WorkerManager.getInstance(project)
        // GRADLE kind without a gradle section: the worker must answer with a stable reason, not crash.
        val cfg = SpotlessConfig(SpotlessConfig.Kind.GRADLE, "8.11.0", project.basePath ?: "/tmp", null, null, emptyMap())
        val loaded = manager.loadConfig(cfg)
        assertFalse(loaded.isOk)
        assertEquals("EXTRACTION_FAILED", loaded.fallbackReason)
    }

    override fun tearDown() {
        try {
            WorkerManager.getInstance(project).shutdown()
        } finally {
            super.tearDown()
        }
    }
}
