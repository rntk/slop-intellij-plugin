package dev.insight.review.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.insight.agent.SourceSide
import dev.insight.codereview.CodeReviewTab
import dev.insight.codereview.codeFile
import dev.insight.codereview.codeSnapshot
import dev.insight.context.ContextActionRegistry
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.ReviewDestination
import dev.insight.diff.DiffReviewTab
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReviewCanvasFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.text.GlyphView
import javax.swing.text.View
import javax.swing.text.html.HTMLDocument

class ReportViewerPlatformTest : FileEditorManagerTestCase() {
    private val sampleJava = """
        package demo;

        public class Widget {
            public int compute(int x) {
                int doubled = x * 2;
                int result = doubled + 1;
                return result;
            }
        }
    """.trimIndent() + "\n"

    private val reportMarkdown = """
        # Audit Report

        - Check `src/demo/Widget.java:L4-L7` (`compute`).
          - Detail at `L5-L6`.
        - Directory `src/demo/`.
    """.trimIndent() + "\n"

    fun `test report viewer opens from tile and destination, indexes project reports, and jumps to exact line`() {
        val widgetFile = myFixture.addFileToProject("src/demo/Widget.java", sampleJava).virtualFile
        myFixture.addFileToProject("reports/audit.md", reportMarkdown)

        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            assertTrue("Stats must stay empty before a report is loaded", tileStats(tab, "view-report-viewer").isEmpty())

            chooseView(tab, "Report viewer")
            assertEquals(CanvasTabKey.ReportViewer, tab.canvas.selectedKey)
            assertTrue(tab.state.reportViewerVisible)

            val builder = tab.canvas.reportViewerBuilder
            PlatformTestUtil.waitWithEventsDispatching("Project report files should be indexed", {
                "reports/audit.md" in builder.view.reportFiles
            }, 10)

            builder.openProjectFile("reports/audit.md")
            PlatformTestUtil.waitWithEventsDispatching("Report should render", {
                builder.view.rendered != null && !builder.view.loading
            }, 10)

            val rendered = builder.view.rendered!!
            val fileLinks = rendered.links.filter { it.kind == ReportTargetKind.FILE }
            assertEquals(2, fileLinks.size)
            assertEquals("src/demo/Widget.java", fileLinks[0].primaryPath)
            assertEquals(4..7, fileLinks[0].lines)
            assertEquals("src/demo/Widget.java", fileLinks[1].primaryPath)
            assertEquals(5..6, fileLinks[1].lines)
            assertTrue(fileLinks[1].contextual)

            assertTrue(rendered.links.any { it.kind == ReportTargetKind.DIRECTORY && it.primaryPath == "src/demo" })
            val panes = descendants(tab.canvas).filterIsInstance<JEditorPane>()
            assertEquals(1, panes.size)

            tab.refreshTiles()
            assertEquals(listOf("3 links", "1 file"), tileStats(tab, "view-report-viewer"))

            // Clicking a resolved link opens the project file at the target line.
            assertTrue(openReportFile(project, "src/demo/Widget.java", fileLinks[1].lines))
            val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
            assertEquals(widgetFile, com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document))
            assertEquals(4, editor.document.getLineNumber(editor.caretModel.offset)) // 0-based for 1-based line 5

            // Switching to raw text keeps links active.
            builder.setRawTextMode(true)
            PlatformTestUtil.waitWithEventsDispatching("Raw text render should complete", {
                builder.view.rendered?.html?.contains("class=\"raw-text\"") == true
            }, 10)
            assertEquals(3, builder.view.rendered!!.links.size)

            // Pasted text works independently of disk files.
            builder.loadPastedText("Look at `Widget.java:L3`")
            PlatformTestUtil.waitWithEventsDispatching("Pasted text should render", {
                builder.view.rendered?.links?.singleOrNull()?.lines == 3..3
            }, 10)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test context picker for file and directory links works across code review and diff review`() {
        myFixture.addFileToProject("src/demo/Widget.java", sampleJava)

        // Code Review context pick
        val codeTab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            codeTab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            val filePick = reportLinkContextPick(
                project = project,
                kind = ReportTargetKind.FILE,
                path = "src/demo/Widget.java",
                lines = 5..6,
                reviewFiles = codeTab.state.snapshotFiles,
                summaryTree = codeTab.state.summaries.tree,
                reviewDestinations = codeTab.reviewDestinations(),
                summaryActions = codeTab.contextSummaryActions,
                openOnWall = { files, title -> codeTab.openCodeWall(files.filterIsInstance<dev.insight.codereview.CodeFile>(), title) },
                openOnCanvas = null,
            )!!
            val kinds = filePick.candidates.map { it.kind }
            assertTrue(ContextScopeKind.SELECTION in kinds)
            assertTrue(ContextScopeKind.LINE in kinds)
            assertTrue(ContextScopeKind.SYMBOL in kinds)
            assertTrue(ContextScopeKind.FILE in kinds)

            val defaultCtx = filePick.candidates[filePick.defaultIndex]
            val codeActions = ContextActionRegistry.getInstance()
                .actions(ContextRequest(project, defaultCtx, filePick.candidates, ContextOrigin.OTHER, filePick.data, null))
                .map { it.id }
            assertTrue("open.editor" in codeActions)
            assertTrue("explore.codeWall" in codeActions)
            assertTrue("review.report_viewer" in codeActions)

            val dirPick = reportLinkContextPick(
                project = project,
                kind = ReportTargetKind.DIRECTORY,
                path = "src/demo",
                lines = null,
                reviewFiles = codeTab.state.snapshotFiles,
                summaryTree = codeTab.state.summaries.tree,
                reviewDestinations = codeTab.reviewDestinations(),
                summaryActions = codeTab.contextSummaryActions,
                openOnWall = { files, title -> codeTab.openCodeWall(files.filterIsInstance<dev.insight.codereview.CodeFile>(), title) },
                openOnCanvas = null,
            )!!
            assertEquals(ContextScopeKind.DIRECTORY, dirPick.candidates.single().kind)
        } finally {
            Disposer.dispose(codeTab)
        }

        // Diff Review destination & context pick
        val diffTab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            val reviewFile = ReviewFile(
                "src/demo/Widget.java",
                "/project/src/demo/Widget.java",
                null,
                "modified",
                "@@ -4,4 +4,4 @@\n-    return x;\n+    return result;\n",
                "fp1",
                before = sampleJava,
                after = sampleJava,
            )
            diffTab.update(ReviewSnapshot(listOf(reviewFile), emptyList()))
            diffTab.reviewDestinations().getValue(ReviewDestination.REPORT_VIEWER)()
            assertEquals(CanvasTabKey.ReportViewer, diffTab.canvas.selectedKey)

            val diffPick = reportLinkContextPick(
                project = project,
                kind = ReportTargetKind.FILE,
                path = "src/demo/Widget.java",
                lines = 5..6,
                reviewFiles = diffTab.state.snapshotFiles,
                summaryTree = diffTab.state.summaries.tree,
                reviewDestinations = diffTab.reviewDestinations(),
                summaryActions = diffTab.contextSummaryActions,
                openOnWall = { files, title -> diffTab.openDiffsWall(files.filterIsInstance<ReviewFile>(), title) },
                openOnCanvas = null,
            )!!
            val diffKinds = diffPick.candidates.map { it.kind }
            assertTrue(ContextScopeKind.SELECTION in diffKinds)
            assertTrue(ContextScopeKind.FILE_DIFF in diffKinds)
            assertTrue(ContextScopeKind.FILE in diffKinds)
            val diffActions = ContextActionRegistry.getInstance()
                .actions(ContextRequest(project, diffPick.candidates[0], diffPick.candidates, ContextOrigin.OTHER, diffPick.data, null))
                .map { it.id }
            assertTrue("open.diff" in diffActions)
            assertTrue("explore.diffsWall" in diffActions)
        } finally {
            Disposer.dispose(diffTab)
        }
    }

    fun `test deleted review file builds diff context pick from before side`() {
        val diffTab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            val deletedFile = ReviewFile(
                "src/demo/Deleted.java",
                "/project/src/demo/Deleted.java",
                "src/demo/Deleted.java",
                "deleted",
                "@@ -1,9 +0,0 @@\n-public class Widget {}\n",
                "fp-del",
                before = sampleJava,
                after = null,
            )
            diffTab.update(ReviewSnapshot(listOf(deletedFile), emptyList()))

            val pick = reportLinkContextPick(
                project = project,
                kind = ReportTargetKind.FILE,
                path = "src/demo/Deleted.java",
                lines = 4..6,
                reviewFiles = diffTab.state.snapshotFiles,
                summaryTree = diffTab.state.summaries.tree,
                reviewDestinations = diffTab.reviewDestinations(),
                summaryActions = diffTab.contextSummaryActions,
                openOnWall = { files, title -> diffTab.openDiffsWall(files.filterIsInstance<ReviewFile>(), title) },
                openOnCanvas = null,
            )
            assertNotNull(pick)
            val nonNullPick = pick!!
            val selection = nonNullPick.candidates.firstOrNull { it.kind == ContextScopeKind.SELECTION }
            assertNotNull(selection)
            assertEquals(SourceSide.BEFORE, selection!!.side)
            assertEquals(4..6, selection.lines)

            val kinds = nonNullPick.candidates.map { it.kind }
            assertTrue(ContextScopeKind.FILE_DIFF in kinds)

            val actions = ContextActionRegistry.getInstance()
                .actions(ContextRequest(project, nonNullPick.candidates[0], nonNullPick.candidates, ContextOrigin.OTHER, nonNullPick.data, null))
                .map { it.id }
            assertTrue("open.diff" in actions)
            assertTrue("explore.diffsWall" in actions)
        } finally {
            Disposer.dispose(diffTab)
        }
    }

    fun `test paste failure shows status notice in empty state and preserves loaded report`() {
        myFixture.addFileToProject("src/demo/Widget.java", sampleJava)
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            chooseView(tab, "Report viewer")
            val builder = tab.canvas.reportViewerBuilder
            PlatformTestUtil.waitWithEventsDispatching("Project report index should load", {
                builder.view.indexLoaded
            }, 10)

            CopyPasteManager.getInstance().setContents(StringSelection(""))
            builder.pasteFromClipboard()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertNull(builder.view.errorMessage)
            assertNull(builder.view.source)
            assertEquals("Clipboard does not contain text.", builder.view.noticeMessage)
            val emptyStatus = descendants(tab.canvas).filterIsInstance<JLabel>()
                .first { it.name == "report-viewer-status" }
            assertEquals("Clipboard does not contain text.", emptyStatus.text)

            builder.loadPastedText("See `src/demo/Widget.java:L3`")
            PlatformTestUtil.waitWithEventsDispatching("Pasted report should render", {
                builder.view.rendered != null && !builder.view.loading
            }, 10)
            assertNull(builder.view.noticeMessage)

            CopyPasteManager.getInstance().setContents(StringSelection("   "))
            builder.pasteFromClipboard()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertNull(builder.view.errorMessage)
            assertNotNull(builder.view.rendered)
            assertEquals(1, descendants(tab.canvas).filterIsInstance<JEditorPane>().size)
            assertEquals("Clipboard does not contain text.", builder.view.noticeMessage)
            val readerStatus = descendants(tab.canvas).filterIsInstance<JLabel>()
                .first { it.name == "report-viewer-status" }
            assertEquals("Clipboard does not contain text.", readerStatus.text)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test switching report directories and reloading after edit renders fresh content`() {
        myFixture.addFileToProject("src/demo/Widget.java", sampleJava)
        myFixture.addFileToProject("docs/first.md", "See Widget.java:L3\n")
        val secondVf = myFixture.addFileToProject("reports/second.md", "See Widget.java:L6\n").virtualFile

        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            chooseView(tab, "Report viewer")
            val builder = tab.canvas.reportViewerBuilder
            PlatformTestUtil.waitWithEventsDispatching("Report files should be indexed", {
                "docs/first.md" in builder.view.reportFiles && "reports/second.md" in builder.view.reportFiles
            }, 10)

            builder.openProjectFile("docs/first.md")
            PlatformTestUtil.waitWithEventsDispatching("First report should render", {
                !builder.view.loading && builder.view.rendered?.links?.singleOrNull()?.lines == 3..3
            }, 10)

            // Switching across directories triggers both index refresh and disk read concurrently.
            builder.openProjectFile("reports/second.md")
            PlatformTestUtil.waitWithEventsDispatching("Second report should render with its own content", {
                !builder.view.loading &&
                    builder.view.source?.label == "reports/second.md" &&
                    builder.view.rendered != null
            }, 10)
            assertEquals("See Widget.java:L6\n", builder.view.rawContent)
            assertEquals(6..6, builder.view.rendered!!.links.single().lines)

            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(secondVf)!!.setText("See Widget.java:L7\n")
            }
            FileDocumentManager.getInstance().saveAllDocuments()

            builder.reload()
            PlatformTestUtil.waitWithEventsDispatching("Reloaded report should reflect edited disk content", {
                !builder.view.loading && builder.view.rendered?.links?.singleOrNull()?.lines == 7..7
            }, 10)
            assertEquals("See Widget.java:L7\n", builder.view.rawContent)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test preferred focus, filter list navigation, and rendered report keyboard scrolling`() {
        myFixture.addFileToProject("src/demo/Widget.java", sampleJava)
        for (i in 1..40) {
            val padded = i.toString().padStart(2, '0')
            val body = (1..80).joinToString("\n") { line ->
                "- Line $line in `src/demo/Widget.java:L4` " + "wide_segment_".repeat(14)
            }
            myFixture.addFileToProject("reports/report-$padded.md", "# Report $padded\n\n$body\n")
        }

        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        val manager = FileEditorManager.getInstance(project)
        try {
            tab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            chooseView(tab, "Report viewer")
            val builder = tab.canvas.reportViewerBuilder
            PlatformTestUtil.waitWithEventsDispatching("Report files should be indexed", {
                builder.view.reportFiles.size >= 40
            }, 10)

            val reportFile = manager.openFiles.filterIsInstance<ReviewCanvasFile>()
                .single { it.name.endsWith("Report viewer") }
            val editor = manager.getEditors(reportFile).single()
            val filterField = descendants(tab.canvas).filterIsInstance<JBTextField>()
                .single { it.name == REPORT_VIEWER_FILTER_NAME }
            assertSame(filterField, editor.preferredFocusedComponent)

            val list = descendants(tab.canvas).filterIsInstance<JBList<*>>()
                .single { it.name == REPORT_VIEWER_FILE_LIST_NAME }
            val listScroll = descendants(tab.canvas).filterIsInstance<JScrollPane>()
                .single { it.name == REPORT_VIEWER_FILE_LIST_SCROLL_NAME }
            fun layoutRecursively(component: java.awt.Component) {
                if (component is Container) {
                    component.doLayout()
                    component.components.forEach(::layoutRecursively)
                }
            }
            val top = generateSequence<java.awt.Component>(listScroll) { it.parent }.last()
            top.setBounds(0, 0, 1000, 400)
            layoutRecursively(top)
            assertTrue("Expected partial list page for visibleRect=${list.visibleRect}",
                list.lastVisibleIndex > list.firstVisibleIndex && list.lastVisibleIndex < 39)
            assertEquals(0, list.selectedIndex)

            fun triggerFilterKey(stroke: String) {
                val key = filterField.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(stroke))
                assertNotNull("Expected filter binding for $stroke", key)
                filterField.actionMap.get(key)!!.actionPerformed(ActionEvent(filterField, ActionEvent.ACTION_PERFORMED, ""))
            }

            triggerFilterKey("DOWN")
            assertEquals(1, list.selectedIndex)
            triggerFilterKey("UP")
            assertEquals(0, list.selectedIndex)
            triggerFilterKey("PAGE_DOWN")
            val pagedIndex = list.selectedIndex
            assertTrue("PAGE_DOWN should advance beyond one item, was $pagedIndex", pagedIndex > 1)
            triggerFilterKey("PAGE_UP")
            assertTrue("PAGE_UP should move back from $pagedIndex, was ${list.selectedIndex}", list.selectedIndex < pagedIndex)
            triggerFilterKey("DOWN")
            val expectedPath = list.selectedValue as String

            filterField.postActionEvent()
            PlatformTestUtil.waitWithEventsDispatching("Enter from filter field should render selected report", {
                !builder.view.loading && builder.view.source?.label == expectedPath && builder.view.rendered != null
            }, 10)

            val pane = descendants(tab.canvas).filterIsInstance<JEditorPane>()
                .single { it.name == REPORT_VIEWER_PANE_NAME }
            assertSame(pane, editor.preferredFocusedComponent)

            val scroll = descendants(tab.canvas).filterIsInstance<JScrollPane>()
                .single { it.name == REPORT_VIEWER_SCROLL_NAME }
            scroll.viewport.extentSize = Dimension(320, 200)
            scroll.viewport.viewSize = Dimension(960, 1400)

            val nav = builder.navigation!!
            nav.navigate(pane, KeyStroke.getKeyStroke("DOWN"))
            val afterDown = scroll.verticalScrollBar.value
            assertTrue(afterDown > 0)
            assertEquals(afterDown, builder.view.scroll)

            nav.navigate(pane, KeyStroke.getKeyStroke("PAGE_DOWN"))
            val afterPageDown = scroll.verticalScrollBar.value
            assertTrue(afterPageDown > afterDown)
            assertEquals(afterPageDown, builder.view.scroll)

            nav.navigate(pane, KeyStroke.getKeyStroke("RIGHT"))
            assertTrue(scroll.horizontalScrollBar.value > 0)

            nav.navigate(pane, KeyStroke.getKeyStroke("END"))
            val bottom = scroll.verticalScrollBar.value
            assertTrue(bottom > afterPageDown)
            assertEquals(bottom, builder.view.scroll)

            nav.navigate(pane, KeyStroke.getKeyStroke("HOME"))
            assertEquals(0, scroll.verticalScrollBar.value)
            assertEquals(0, builder.view.scroll)

            val editorPage = tab.canvas.editorPage(CanvasTabKey.ReportViewer)
            nav.navigate(editorPage, KeyStroke.getKeyStroke("DOWN"))
            assertTrue(scroll.verticalScrollBar.value > 0)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test report viewer scales rendered markdown and raw text font by 20 percent`() {
        myFixture.addFileToProject("src/demo/Widget.java", sampleJava)
        myFixture.addFileToProject("reports/audit.md", reportMarkdown)

        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeFile("src/demo/Widget.java", sampleJava)))
            chooseView(tab, "Report viewer")
            val builder = tab.canvas.reportViewerBuilder
            PlatformTestUtil.waitWithEventsDispatching("Project report files should be indexed", {
                "reports/audit.md" in builder.view.reportFiles
            }, 10)

            builder.openProjectFile("reports/audit.md")
            PlatformTestUtil.waitWithEventsDispatching("Report should render", {
                builder.view.rendered != null && !builder.view.loading
            }, 10)

            val baseFont = UIUtil.getLabelFont()
            val expectedBodySize = Math.round(baseFont.size2D * REPORT_TEXT_FONT_SCALE)
            val mdPane = descendants(tab.canvas).filterIsInstance<JEditorPane>()
                .single { it.name == REPORT_VIEWER_PANE_NAME }
            assertEquals(baseFont.size2D * REPORT_TEXT_FONT_SCALE, mdPane.font.size2D, 0.01f)
            assertEquals(expectedBodySize, mdPane.font.size)

            val mdGlyphs = collectGlyphFontSizes(mdPane)
            val headingSize = mdGlyphs.first { "Audit" in it.first }.second
            val bodySize = mdGlyphs.first { "Check" in it.first }.second
            val codeSize = mdGlyphs.first { "src/demo/Widget.java:L4-L7" in it.first }.second
            assertEquals(Math.round(expectedBodySize * 1.45f), headingSize)
            assertTrue(headingSize > expectedBodySize)
            assertEquals(expectedBodySize, bodySize)
            assertEquals(Math.round(expectedBodySize * 0.94f), codeSize)

            builder.setRawTextMode(true)
            PlatformTestUtil.waitWithEventsDispatching("Raw text render should complete", {
                builder.view.rendered?.html?.contains("class=\"raw-text\"") == true
            }, 10)

            val rawPane = descendants(tab.canvas).filterIsInstance<JEditorPane>()
                .single { it.name == REPORT_VIEWER_PANE_NAME }
            assertEquals(baseFont.size2D * REPORT_TEXT_FONT_SCALE, rawPane.font.size2D, 0.01f)
            assertEquals(expectedBodySize, rawPane.font.size)

            val rawGlyphs = collectGlyphFontSizes(rawPane)
            val rawTextSize = rawGlyphs.first { "Audit" in it.first || "Widget.java" in it.first }.second
            assertEquals(Math.round(expectedBodySize * 0.94f), rawTextSize)
        } finally {
            Disposer.dispose(tab)
        }
    }

    private fun collectGlyphFontSizes(pane: JEditorPane): List<Pair<String, Int>> {
        pane.setSize(800, 600)
        pane.preferredSize
        pane.doLayout()
        val doc = pane.document as HTMLDocument
        val result = mutableListOf<Pair<String, Int>>()
        fun visit(view: View) {
            if (view is GlyphView) {
                val text = doc.getText(view.startOffset, view.endOffset - view.startOffset).trim()
                if (text.isNotEmpty()) result += text to view.font.size
            }
            for (i in 0 until view.viewCount) visit(view.getView(i))
        }
        visit(pane.ui.getRootView(pane))
        return result
    }

    private fun descendants(container: Container): List<java.awt.Component> = (if (container is NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
