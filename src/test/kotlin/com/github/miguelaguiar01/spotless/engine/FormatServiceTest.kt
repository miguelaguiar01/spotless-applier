package com.github.miguelaguiar01.spotless.engine

import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.github.miguelaguiar01.spotless.worker.WorkerManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking

/** Document application: minimal replacement, caret survival, single undo step, CLEAN leaves the document untouched. */
class FormatServiceTest : BasePlatformTestCase() {

    /** The service suspends on write commands that need the EDT; the test body must not hold it. */
    override fun runInDispatchThread(): Boolean = false

    private fun config() = SpotlessConfig(
        SpotlessConfig.Kind.BUILTIN_TEST, null, project.basePath ?: "/tmp", null, null,
        mapOf("steps" to "trimTrailingWhitespace,endWithNewline"),
    )

    fun testFormatsDocumentKeepsCaretAndIsUndoable() {
        runInEdtAndWait { myFixture.configureByText("A.java", "class A {\n  int <caret>x;   \n}") }
        val document = myFixture.editor.document
        val before = document.text
        val caretBefore = com.intellij.openapi.application.ReadAction.compute<Int, RuntimeException> { myFixture.editor.caretModel.offset }

        val outcome = runBlocking {
            FormatService.getInstance(project).formatDocumentWith(config(), document, "/tmp/A.java", "A.java")
        }
        assertEquals(FormatOutcome.Formatted(changed = true), outcome)
        assertEquals("class A {\n  int x;\n}\n", document.text)
        val caretAfter = com.intellij.openapi.application.ReadAction.compute<Int, RuntimeException> { myFixture.editor.caretModel.offset }
        assertEquals("caret before the changed region must not move", caretBefore, caretAfter)
        assertFalse(document.text == before)

        runInEdtAndWait { myFixture.performEditorAction(IdeActions.ACTION_UNDO) }
        assertEquals("one undo restores the original", before, document.text)
    }

    fun testCleanDocumentIsNotModified() {
        runInEdtAndWait { myFixture.configureByText("B.java", "class B {}\n") }
        val document = myFixture.editor.document
        val stamp = document.modificationStamp
        val outcome = runBlocking {
            FormatService.getInstance(project).formatDocumentWith(config(), document, "/tmp/B.java", "B.java")
        }
        assertEquals(FormatOutcome.Clean, outcome)
        assertEquals(stamp, document.modificationStamp)
    }

    fun testMinimalDiffHelpers() {
        assertEquals(3, FormatService.commonPrefix("abcXYZ", "abcQ"))
        assertEquals(2, FormatService.commonSuffix("abcXYZ", "abcQYZ", 3))
        assertEquals(0, FormatService.commonSuffix("aa", "aa", 2))
    }

    override fun tearDown() {
        try {
            WorkerManager.getInstance(project).shutdown()
        } finally {
            super.tearDown()
        }
    }
}
