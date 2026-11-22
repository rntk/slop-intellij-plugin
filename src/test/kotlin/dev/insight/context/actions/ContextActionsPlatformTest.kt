package dev.insight.context.actions

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.agent.SourceSide
import dev.insight.codereview.CodeFile
import dev.insight.context.CodeContext
import dev.insight.context.ContextAction
import dev.insight.context.ContextActionRegistry
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.ContextSummaryActions
import dev.insight.context.ContextData
import dev.insight.context.ReviewDestination
import dev.insight.context.ContextPick
import dev.insight.context.editorPick
import dev.insight.context.toMarkdown
import dev.insight.context.directoryScope
import dev.insight.diff.ReviewFile
import dev.insight.review.SummaryKind
import java.awt.datatransfer.DataFlavor

/** The built-in context actions registered through the extension point, run through [ContextAction.perform]. */
class ContextActionsPlatformTest : BasePlatformTestCase() {
    private val source = """
        class Outer {
            int value;

            void render(int x) {
                int y = <caret>x + 1;
                value = y;
            }
        }
    """.trimIndent()

    private fun pick(): ContextPick {
        val file = myFixture.configureByText("Outer.java", source)
        return editorPick(project, ContextOrigin.EDITOR, myFixture.editor, file.virtualFile, file)
    }

    private fun request(pick: ContextPick, kind: ContextScopeKind, symbolName: String? = null): ContextRequest {
        val context = pick.candidates.first { it.kind == kind && (symbolName == null || it.symbol?.name == symbolName) }
        return ContextRequest(project, context, pick.candidates, pick.origin, pick.data, null)
    }

    private fun action(id: String) = ContextAction.EP_NAME.extensionList.first { it.id == id }

    fun testEveryBuiltInActionIsRegisteredWithAUniqueId() {
        val ids = ContextAction.EP_NAME.extensionList.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.toString(), ids.containsAll(listOf("open.editor", "open.diff", "explore.canvas", "explore.callGraph", "explore.flowChart",
            "explore.sideBySide", "explore.summaryTree", "explore.summarizeFile", "explore.summarizeFileDiff", "explore.summarizeDirectory",
            "ask.chat", "ask.reportSelection", "edit.deleteReportSelection", "open.linkedEditors", "explore.linkedCanvas", "copy.markdown")))
        for (action in ContextAction.EP_NAME.extensionList) {
            assertTrue(action.id, action.title.isNotBlank() && !action.title.startsWith("!"))
            assertTrue(action.id, action.group in listOf("Open", "Explore", "Ask", "Edit", "Copy", "Review views"))
        }
    }

    fun testRegistryOffersTheActionsOfAFunctionSymbol() {
        val request = request(pick(), ContextScopeKind.SYMBOL, "render")
        val ids = ContextActionRegistry.getInstance().actions(request).map { it.id }
        assertEquals(setOf("open.editor", "explore.canvas", "explore.codeWall", "explore.callGraph", "explore.flowChart", "explore.sideBySide",
            "explore.summarizeFile", "ask.chat", "copy.markdown"),
            ids.toSet())
    }

    fun testSummaryGenerationUsesTheOwningReviewAndSelectedScope() {
        val requested = mutableListOf<Pair<String, SummaryKind>>()
        val data = ContextData().put(ContextKeys.SUMMARY_GENERATION, ContextSummaryActions(
            { path, kind -> (path to kind) !in requested }, { path, kind -> requested.add(path to kind) },
        ))
        fun summarize(context: CodeContext, expected: String) {
            val request = ContextRequest(project, context, listOf(context), ContextOrigin.OTHER, data, null)
            val actions = ContextActionRegistry.getInstance().actions(request).filter { it.id.startsWith("explore.summarize") }
            assertEquals(listOf(expected), actions.map { it.id })
            actions.single().perform(request)
            assertFalse(actions.single().isAvailable(request))
        }
        val source = request(pick(), ContextScopeKind.SYMBOL, "render").context
        summarize(source, "explore.summarizeFile")
        val directory = directoryScope("src", listOf(CodeFile("src/Only.java", "/repo/src/Only.java", "text")))
        summarize(directory, "explore.summarizeDirectory")
        val renamed = ReviewFile("src/New.java", "/repo/src/New.java", "src/Old.java", "renamed", "patch", "id")
        data.put(ContextKeys.REVIEW_FILE, renamed)
        val before = source.copy(path = renamed.previousPath, side = SourceSide.BEFORE)
        summarize(before, "explore.summarizeFileDiff")
        val change = before.copy(kind = ContextScopeKind.CHANGE, path = null, files = listOf(renamed.path, source.path!!))
        val request = ContextRequest(project, change, listOf(change), ContextOrigin.OTHER, data, null)
        assertTrue(ContextActionRegistry.getInstance().actions(request).none { it.id.startsWith("explore.summarize") })
        assertEquals(listOf(source.path to SummaryKind.FILE, "src" to SummaryKind.DIRECTORY, renamed.path to SummaryKind.FILE), requested)
    }

    fun testDiffsWallRequiresHostAndReceivesTheSelectedContext() {
        val original = request(pick(), ContextScopeKind.LINE)
        val wall = action("explore.diffsWall")
        assertFalse(wall.isAvailable(original))
        var chosen: dev.insight.context.CodeContext? = null
        val data = ContextData().put(ContextKeys.OPEN_ON_DIFFS_WALL) { context -> chosen = context }
        val request = ContextRequest(project, original.context, original.candidates, ContextOrigin.OTHER, data, null)
        assertTrue(wall.isAvailable(request))
        wall.perform(request)
        assertEquals(original.context, chosen)
        assertFalse(wall.isAvailable(ContextRequest(project, original.context, original.candidates, ContextOrigin.DIFFS_WALL, data, null)))
    }

    fun testReviewViewsUseOnlyHostDestinations() {
        val original = request(pick(), ContextScopeKind.LINE)
        val registered = ContextAction.EP_NAME.extensionList.filter { it.group == "Review views" }
        assertEquals(ReviewDestination.entries.map { "review.${it.name.lowercase()}" }.toSet(), registered.map { it.id }.toSet())
        val actions = listOf(SessionSummaryTreeAction(), SessionTopicsAction(), SessionWordsAction())
        actions.forEach { assertFalse(it.isAvailable(original)) }
        val opened = mutableListOf<ReviewDestination>()
        val destinations = listOf(ReviewDestination.TREE, ReviewDestination.TOPICS, ReviewDestination.WORDS)
        val data = ContextData().put(ContextKeys.REVIEW_DESTINATIONS,
            destinations.associateWith { destination -> { opened.add(destination); Unit } })
        val request = ContextRequest(project, original.context, original.candidates, original.origin, data, null)
        actions.forEach { assertTrue(it.isAvailable(request)); it.perform(request) }
        assertEquals(destinations, opened)
        assertFalse(SessionMovesAction().isAvailable(request))
    }

    fun testCopyPutsTheMarkdownOnTheClipboard() {
        val request = request(pick(), ContextScopeKind.SYMBOL, "render")
        action("copy.markdown").perform(request)
        val copied = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertEquals(request.context.toMarkdown(), copied)
        assertTrue(copied!!.contains("void render(int x)"))
    }

    fun testOpenInEditorSelectsTheRange() {
        val pick = pick()
        val request = request(pick, ContextScopeKind.SYMBOL, "render")
        // Move away so that opening has something to do.
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.editor.selectionModel.removeSelection()
        action("open.editor").perform(request)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val document = editor.document
        assertEquals(document.getLineStartOffset(3), editor.selectionModel.selectionStart)
        assertEquals(document.getLineEndOffset(6), editor.selectionModel.selectionEnd)
        assertEquals(3, document.getLineNumber(editor.caretModel.offset))
    }

    fun testOpenInEditorFindsAProjectFileWithoutAHostFile() {
        val pick = pick()
        val request = request(pick, ContextScopeKind.LINE)
        val data = ContextData()
        assertNull(data[ContextKeys.VIRTUAL_FILE])
        val bare = ContextRequest(project, request.context, request.candidates, ContextOrigin.CODE_WALL, data, null)
        // The path is project-relative to the in-memory root, so only the absolute path resolves it.
        assertTrue(action("open.editor").isAvailable(bare))
        action("open.editor").perform(bare)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(4, editor.document.getLineNumber(editor.selectionModel.selectionStart))
    }
}
