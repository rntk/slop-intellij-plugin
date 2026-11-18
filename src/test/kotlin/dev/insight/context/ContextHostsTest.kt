package dev.insight.context

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.agent.SourceSide
import dev.insight.context.actions.ContextSummaryGeneration
import dev.insight.diff.ReviewFile
import dev.insight.diff.wallLineSide
import dev.insight.review.wall.WallLine

/** The scopes the walls and the diff viewer hand the picker, from captured text. */
class ContextHostsTest : BasePlatformTestCase() {
    private val before = "class A {\n    void run() {\n        int x = 1;\n    }\n}\n"
    private val after = "class A {\n    void run() {\n        int x = 2;\n        int y = 3;\n    }\n}\n"
    private val patch = "@@ -2,3 +2,4 @@\n     void run() {\n-        int x = 1;\n+        int x = 2;\n+        int y = 3;\n     }\n"
    private val file = ReviewFile("src/A.java", "/nowhere/src/A.java", null, "modified", patch, "1", before, after)
    private val other = ReviewFile("src/B.java", "/nowhere/src/B.java", null, "modified", "@@ -1 +1 @@\n-a\n+b\n", "2", "a\n", "b\n")

    fun testDiffPickOnTheAfterSideHasSymbolHunkAndChange() {
        val pick = reviewDiffPick(project, ContextOrigin.DIFFS_WALL, file, 4, SourceSide.AFTER, listOf(file, other))
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.SYMBOL, ContextScopeKind.SYMBOL, ContextScopeKind.HUNK,
            ContextScopeKind.FILE_DIFF, ContextScopeKind.FILE, ContextScopeKind.CHANGE), pick.candidates.map { it.kind })
        assertEquals("int y = 3;", pick.candidates[0].text.trim())
        assertEquals("run", pick.candidates[1].symbol!!.name)
        assertEquals(2..5, pick.candidates[1].lines)
        assertEquals(file, pick.data[ContextKeys.REVIEW_FILE])
        assertEquals(ContextOrigin.DIFFS_WALL, pick.origin)
        assertEquals(listOf("src/A.java", "src/B.java"), pick.candidates.last().files)
        assertEquals(1, pick.defaultIndex)
    }

    fun testDiffPickOnTheBeforeSideParsesTheOldText() {
        val pick = reviewDiffPick(project, ContextOrigin.IDE_DIFF, file, 3, SourceSide.BEFORE, null)
        val symbols = pick.candidates.filter { it.kind == ContextScopeKind.SYMBOL }
        assertEquals(listOf("run", "A"), symbols.map { it.symbol!!.name })
        assertEquals(2..4, symbols[0].lines)
        assertTrue(pick.candidates.none { it.kind == ContextScopeKind.CHANGE })
        assertTrue(pick.candidates.all { it.side == SourceSide.BEFORE })
    }

    fun testDiffPickWithoutALineOffersTheDiffAndFileOnly() {
        val pick = reviewDiffPick(project, ContextOrigin.IDE_DIFF, file, null, SourceSide.AFTER, null)
        assertEquals(listOf(ContextScopeKind.FILE_DIFF, ContextScopeKind.FILE), pick.candidates.map { it.kind })
    }

    fun testCapturedSourcePick() {
        val pick = capturedSourcePick(project, ContextOrigin.CODE_WALL, "src/A.java", null, after, 3)
        assertEquals(ContextScopeKind.LINE, pick.candidates.first().kind)
        assertEquals(listOf("run", "A"), pick.candidates.filter { it.kind == ContextScopeKind.SYMBOL }.map { it.symbol!!.name })
        assertEquals(ContextOrigin.CODE_WALL, pick.origin)
        val header = capturedSourcePick(project, ContextOrigin.CODE_WALL, "src/A.java", null, after, null)
        assertEquals(listOf(ContextScopeKind.FILE), header.candidates.map { it.kind })
    }

    fun testFileSummaryUsesFullCapturedSourceBeyondPreview() {
        val text = "val value = 1\n".repeat(MAX_CONTEXT_CHARS / 14 + 1) + "// last source line\n"
        val pick = capturedSourcePick(project, ContextOrigin.EDITOR, "Source.kt", null, text, null)
        val candidate = pick.candidates.single()
        assertTrue(candidate.text.contains("truncated"))
        assertEquals(text, pick.data[SUMMARY_SOURCE_TEXT])
        assertTrue(ContextSummaryGeneration.canGenerate(ContextRequest(project, candidate, pick.candidates, pick.origin, pick.data, null)))
    }

    fun testStandaloneDiffSummaryUsesBothDisplayedSides() {
        myFixture.configureByText("A.java", after)
        val editor = myFixture.editor
        val factory = DiffContentFactory.getInstance()
        val comparison = SimpleDiffRequest("A.java", factory.create(project, before), factory.create(project, editor.document),
            "Base commit", "Other commit")
        val pick = standaloneDiffPick(project, TestActionEvent.createTestEvent(), editor, comparison) { null }!!
        val evidence = pick.data[EDITOR_DIFF_SUMMARY]!!
        val captured = evidence.reviewFile()
        assertEquals(before, captured.before)
        assertEquals(after, captured.after)
        assertEquals("Base commit", captured.beforeLabel)
        assertEquals("Other commit", captured.afterLabel)
        assertTrue(captured.patch.contains("-        int x = 1;"))
        assertTrue(captured.patch.contains("+        int x = 2;"))
        val scope = CodeContext(ContextScopeKind.FILE, "File", file.path, file.absolutePath, null, SourceSide.BEFORE, before)
        val data = ContextData().put(SUMMARY_SOURCE_TEXT, before)
        val request = ContextRequest(project, scope, listOf(scope), ContextOrigin.IDE_DIFF, data, null)
        assertFalse(ContextSummaryGeneration.canGenerate(request))
        data.put(EDITOR_DIFF_SUMMARY, evidence)
        assertTrue(ContextSummaryGeneration.canGenerate(request))
    }

    fun testRenamedDiffSummaryAcceptsPreviousPath() {
        val renamed = file.copy(previousPath = "src/Old.java")
        val pick = reviewDiffPick(project, ContextOrigin.IDE_DIFF, renamed, 3, SourceSide.BEFORE, null)
        val oldScope = pick.candidates.first().copy(path = renamed.previousPath)
        assertTrue(ContextSummaryGeneration.canGenerate(ContextRequest(project, oldScope, listOf(oldScope), pick.origin, pick.data, null)))
    }

    fun testProjectDirectorySummaryRequiresDirectoryEvidence() {
        val source = myFixture.addFileToProject("src/A.kt", "val a = 1").virtualFile
        val scope = CodeContext(ContextScopeKind.DIRECTORY, "Directory src", "src", source.parent.path, null,
            SourceSide.CURRENT, "A.kt", files = emptyList())
        val data = ContextData().put(ContextKeys.VIRTUAL_FILE, source)
        val request = ContextRequest(project, scope, listOf(scope), ContextOrigin.OTHER, data, null)
        assertFalse(ContextSummaryGeneration.canGenerate(request))
        data.put(ContextKeys.VIRTUAL_FILE, source.parent)
        assertTrue(ContextSummaryGeneration.canGenerate(request))
    }

    fun testWallRowsMapToASideAndLine() {
        assertEquals(SourceSide.AFTER to 7, wallLineSide(WallLine(WallLine.Kind.ADDED, "+x", null, 7)))
        assertEquals(SourceSide.BEFORE to 3, wallLineSide(WallLine(WallLine.Kind.REMOVED, "-x", 3, null)))
        assertEquals(SourceSide.AFTER to 5, wallLineSide(WallLine(WallLine.Kind.CONTEXT, " x", 4, 5)))
        assertEquals(SourceSide.AFTER to null, wallLineSide(WallLine(WallLine.Kind.HUNK, "@@")))
        assertEquals(SourceSide.AFTER to null, wallLineSide(null))
    }
}
