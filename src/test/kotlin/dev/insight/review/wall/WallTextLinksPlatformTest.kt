package dev.insight.review.wall

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.codereview.CodeReviewTab
import dev.insight.codereview.codeFile
import dev.insight.codereview.codeSnapshot
import dev.insight.diff.DiffReviewTab
import dev.insight.diff.DiffsWallContent
import dev.insight.diff.DiffsWallView
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.llm.Completion
import dev.insight.review.ReportTargetKind
import dev.insight.review.SummaryKind
import dev.insight.review.summarize
import dev.insight.review.ui.LinkedTextPane
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.chooseView
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.KeyStroke

class WallTextLinksPlatformTest : BasePlatformTestCase() {
    private fun descendants(container: Container): List<Component> = (if (container is NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun blocks(canvas: NoteCanvas) = descendants(canvas).filterIsInstance<WallView.BlockView>()

    private fun surfaceOf(canvas: NoteCanvas) = descendants(canvas).filterIsInstance<WallView.Surface>().single()

    private fun waitForLinks(pane: LinkedTextPane, predicate: (LinkedTextPane) -> Boolean = { it.renderedLinks.isNotEmpty() }) {
        pane.addNotify()
        PlatformTestUtil.waitWithEventsDispatching("LinkedTextPane should resolve links", {
            predicate(pane)
        }, 10)
    }

    private fun pressKey(surface: JComponent, stroke: String) {
        surface.getActionForKeyStroke(KeyStroke.getKeyStroke(stroke))!!
            .actionPerformed(ActionEvent(surface, 0, null))
    }

    private fun paintBlock(block: WallView.BlockView) {
        val image = BufferedImage(block.width.coerceAtLeast(1), block.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            block.paint(g)
        } finally {
            g.dispose()
        }
    }

    private fun clickSummaryToggle(block: WallView.BlockView) {
        paintBlock(block)
        val toggle = block.summaryToggle!!
        block.dispatchEvent(MouseEvent(block, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK,
            toggle.centerX.toInt(), toggle.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
    }

    fun `test diffs wall summary links scroll to existing card, add non-diff card, and navigate via symbols`() {
        val extraText = (1..20).joinToString("\n") { n ->
            when (n) {
                7 -> "fun computeHelper(x: Int) = x + 1"
                else -> "// helper line $n"
            }
        } + "\n"
        val aText = "fun alpha() = computeHelper(1)\nfun secondLine() = 2\n"
        val bText = "fun beta() = 42\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        val bVf = myFixture.addFileToProject("src/B.kt", bText).virtualFile
        myFixture.addFileToProject("src/Helper.kt", extraText)

        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified",
            "@@ -1,2 +1,2 @@\n-fun alpha() = 0\n+fun alpha() = computeHelper(1)\n fun secondLine() = 2\n", "a",
            before = "fun alpha() = 0\nfun secondLine() = 2\n", after = aText)
        val diffB = ReviewFile("src/B.kt", bVf.path, null, "modified",
            "@@ -1 +1 @@\n-fun beta() = 1\n+fun beta() = 42\n", "b",
            before = "fun beta() = 1\n", after = bText)

        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(ReviewSnapshot(listOf(diffA, diffB), emptyList()))
            val tree = tab.state.summaries.tree
            val aId = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/A.kt" }.id
            tab.state.summarize(tab.state.revision, mapOf(
                aId to "See `L2`, then call `computeHelper` in `src/Helper.kt:L7` and update `src/B.kt:L1` near `L2`.",
            ))
            tab.update(ReviewSnapshot(listOf(diffA, diffB), emptyList()))
            chooseView(tab, "Diffs wall")

            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1400, 900)
            layoutTree(scrollPane)

            val blockA = blocks(tab.canvas).single { it.block.path == "src/A.kt" }
            val startA = blockA.location
            val blockB = blocks(tab.canvas).single { it.block.path == "src/B.kt" }
            val startB = blockB.location

            clickSummaryToggle(blockA)
            assertTrue(blockA.showsSummary)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { pane ->
                pane.renderedLinks.any { it.kind == ReportTargetKind.SYMBOL } &&
                    pane.renderedLinks.any { it.contextual && it.primaryPath == "src/A.kt" && it.lines == 2..2 } &&
                    pane.renderedLinks.any { it.primaryPath == "src/B.kt" } &&
                    pane.renderedLinks.any { it.primaryPath == "src/Helper.kt" } &&
                    pane.renderedLinks.any { it.contextual && it.primaryPath == "src/B.kt" && it.lines == 2..2 }
            }

            // Leading bare L2 resolves via defaultFile to src/A.kt, switches back from summary to diff body, and highlights line 2.
            val linkToSelfL2 = summaryPane.renderedLinks.first { it.contextual && it.primaryPath == "src/A.kt" && it.lines == 2..2 }
            summaryPane.activateLink(linkToSelfL2)
            val updatedA = blocks(tab.canvas).single { it.block.path == "src/A.kt" }
            assertFalse("Line link on same card should reveal diff body", updatedA.showsSummary)
            assertTrue(updatedA.active)
            assertEquals(setOf(3), updatedA.highlightedRows())

            // Link to a card already on the wall (src/B.kt:L1) activates it and highlights row 2 (+new beta).
            val linkToB = summaryPane.renderedLinks.first { it.primaryPath == "src/B.kt" && it.lines == 1..1 }
            summaryPane.activateLink(linkToB)
            val updatedB = blocks(tab.canvas).single { it.block.path == "src/B.kt" }
            assertTrue("Target card on wall should become active", updatedB.active)
            assertEquals(setOf(2), updatedB.highlightedRows())
            assertEquals(setOf("src/A.kt", "src/B.kt"), blocks(tab.canvas).map { it.block.path }.toSet())

            // Link to an unchanged file not in the diff adds a source card and scrolls to line 7 without moving existing cards.
            val linkToHelper = summaryPane.renderedLinks.first { it.primaryPath == "src/Helper.kt" && it.lines == 7..7 }
            summaryPane.activateLink(linkToHelper)
            val helperBlock = blocks(tab.canvas).singleOrNull { it.block.path == "src/Helper.kt" }
            assertNotNull("Missing project file should be added as a card", helperBlock)
            assertTrue("Added card should carry 'not in diff' stats", helperBlock!!.block.stats.contains("not in diff"))
            assertTrue("Added card should become active", helperBlock.active)
            assertEquals(setOf(6), helperBlock.highlightedRows())
            pressKey(surface, "ctrl 0")
            layoutTree(scrollPane)
            assertEquals("Existing block A keeps its position", startA, blocks(tab.canvas).single { it.block.path == "src/A.kt" }.location)
            assertEquals("Existing block B keeps its position", startB, blocks(tab.canvas).single { it.block.path == "src/B.kt" }.location)

            // Removing the added card via its menu removes it from the wall, then symbol link adds it back.
            val removeItem = helperBlock.buildMenu().components.filterIsInstance<JMenuItem>().single { it.text == "Remove from wall" }
            removeItem.doClick()
            assertEquals(setOf("src/A.kt", "src/B.kt"), blocks(tab.canvas).map { it.block.path }.toSet())

            val symbolLink = summaryPane.renderedLinks.first { it.kind == ReportTargetKind.SYMBOL && it.symbol?.name == "computeHelper" }
            summaryPane.activateLink(symbolLink)
            PlatformTestUtil.waitWithEventsDispatching("Symbol lookup should add Helper.kt back onto the wall", {
                blocks(tab.canvas).any { it.block.path == "src/Helper.kt" && it.active }
            }, 10)
            val reAdded = blocks(tab.canvas).single { it.block.path == "src/Helper.kt" }
            assertEquals(setOf(6), reAdded.highlightedRows())
            assertEquals(1, blocks(tab.canvas).count { it.block.path == "src/Helper.kt" })
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test subset diffs wall adds another changed file as a diff card and canvas chat links work`() {
        val aVf = myFixture.addFileToProject("src/A.kt", "fun a() = 1\n").virtualFile
        val bVf = myFixture.addFileToProject("src/B.kt", "fun b() = 2\n").virtualFile
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", "@@ -1 +1 @@\n-old\n+fun a() = 1\n", "a",
            before = "old\n", after = "fun a() = 1\n")
        val diffB = ReviewFile("src/B.kt", bVf.path, null, "modified", "@@ -1 +1 @@\n-old\n+fun b() = 2\n", "b",
            before = "old\n", after = "fun b() = 2\n")

        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = {
            Completion("See `src/B.kt:L1` for the matching change.")
        })
        try {
            tab.update(ReviewSnapshot(listOf(diffA, diffB), emptyList()))
            tab.openDiffsWall(listOf(diffA), "Subset")
            assertEquals(listOf("src/A.kt"), blocks(tab.canvas).map { it.block.path })

            descendants(tab.canvas).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-canvas-chat-link" }.doClick()
            val chatPanel = descendants(tab.canvas).filterIsInstance<WallChatPanel>().single()
            chatPanel.send("Where is the rest?")
            PlatformTestUtil.waitWithEventsDispatching("Chat reply should complete", {
                descendants(chatPanel).filterIsInstance<LinkedTextPane>().any { "src/B.kt:L1" in it.markdown }
            }, 10)
            val replyPane = descendants(chatPanel).filterIsInstance<LinkedTextPane>().last { "src/B.kt:L1" in it.markdown }
            waitForLinks(replyPane) { it.renderedLinks.any { l -> l.primaryPath == "src/B.kt" } }

            val linkToB = replyPane.renderedLinks.first { it.primaryPath == "src/B.kt" }
            replyPane.activateLink(linkToB)
            val addedB = blocks(tab.canvas).singleOrNull { it.block.path == "src/B.kt" }
            assertNotNull("Subset wall should pull in missing diff file", addedB)
            assertEquals("+1 −1", addedB!!.block.stats)
            assertTrue(addedB.active)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test code wall renders markdown summary links, scrolls in skim mode, and adds outside-scope files`() {
        val aText = "fun a() = c()\n"
        val bText = "fun b() = 2\n"
        val cText = "fun c() = 3\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        val bVf = myFixture.addFileToProject("src/B.kt", bText).virtualFile
        myFixture.addFileToProject("lib/C.kt", cText)

        val codeA = codeFile("src/A.kt", aText).let { CodeFileShim.withAbs(it, aVf.path) }
        val codeB = codeFile("src/B.kt", bText).let { CodeFileShim.withAbs(it, bVf.path) }

        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeA, codeB))
            val tree = tab.state.summaries.tree
            val aId = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/A.kt" }.id
            tab.state.summarize(tab.state.revision, mapOf(aId to "Delegates to `lib/C.kt:L1` and `src/B.kt:L1`."))
            tab.update(codeSnapshot(codeA, codeB))
            tab.openCodeWall(listOf(codeA), "Only A")

            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1400, 900)
            layoutTree(scrollPane)

            // Switch to SKIM mode and toggle summary on A.kt.
            pressKey(surface, "M")
            layoutTree(scrollPane)
            val blockA = blocks(tab.canvas).single { it.block.path == "src/A.kt" }
            clickSummaryToggle(blockA)
            assertTrue(blockA.showsSummary)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { pane ->
                pane.renderedLinks.any { it.primaryPath == "lib/C.kt" } &&
                    pane.renderedLinks.any { it.primaryPath == "src/B.kt" }
            }

            // Follow link to scope file B (adds scope card) and outside-scope file C (adds source card).
            summaryPane.activateLink(summaryPane.renderedLinks.first { it.primaryPath == "src/B.kt" })
            assertNotNull(blocks(tab.canvas).singleOrNull { it.block.path == "src/B.kt" })

            summaryPane.activateLink(summaryPane.renderedLinks.first { it.primaryPath == "lib/C.kt" })
            val blockC = blocks(tab.canvas).singleOrNull { it.block.path == "lib/C.kt" }
            assertNotNull(blockC)
            assertTrue("Outside-scope card should be marked", blockC!!.block.stats.contains("outside scope"))
            assertTrue(blockC.active)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test rich summary keyboard scrolling preserves diff scroll in cards mode`() {
        val longDiff = "@@ -1,300 +1,300 @@\n" + (1..300).joinToString("\n") { "+line $it" } + "\n"
        val aVf = myFixture.addFileToProject("src/A.kt", (1..300).joinToString("\n") { "line $it" }).virtualFile
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", longDiff, "a", before = "", after = "x")
        val longSummary = (1..80).joinToString("\n\n") { "Paragraph $it mentioning `src/A.kt:L1`." }

        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(ReviewSnapshot(listOf(diffA), emptyList()))
            val tree = tab.state.summaries.tree
            val aId = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/A.kt" }.id
            tab.state.summarize(tab.state.revision, mapOf(aId to longSummary))
            tab.update(ReviewSnapshot(listOf(diffA), emptyList()))
            chooseView(tab, "Diffs wall")

            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1400, 900)
            layoutTree(scrollPane)

            pressKey(surface, "TAB")
            pressKey(surface, "DOWN")
            val block = blocks(tab.canvas).single()
            val diffScroll = block.bodyScroll
            assertTrue(diffScroll.y > 0)

            clickSummaryToggle(block)
            assertTrue(block.showsSummary)
            val pane = block.summaryTextPane!!
            waitForLinks(pane) { it.document.length > 0 && it.renderedLinks.isNotEmpty() }
            layoutTree(scrollPane)

            assertEquals(Point(), block.bodyScroll)
            pressKey(surface, "SPACE")
            assertTrue("SPACE should scroll rich summary", block.bodyScroll.y > 0)
            pressKey(surface, "END")
            val end = block.bodyScroll
            pressKey(surface, "DOWN")
            assertEquals(end, block.bodyScroll)
            pressKey(surface, "HOME")
            assertEquals(Point(), block.bodyScroll)

            clickSummaryToggle(block)
            assertFalse(block.showsSummary)
            assertEquals(diffScroll, block.bodyScroll)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test link to unloaded scope file loads only that card on demand without external badge`() {
        val files = (0 until 5).map { i ->
            val path = "src/F$i.kt"
            val text = "fun f$i() = 1\nfun target$i() = $i\n"
            val vf = myFixture.addFileToProject(path, text).virtualFile
            ReviewFile(
                path = path,
                absolutePath = vf.path,
                previousPath = null,
                status = "modified",
                patch = "@@ -1,2 +1,2 @@\n fun f$i() = 1\n-fun target$i() = 0\n+fun target$i() = $i\n",
                fingerprint = "fp$i",
                before = "fun f$i() = 1\nfun target$i() = 0\n",
                after = text,
            )
        }
        val wall = DiffsWallView(
            openDiff = { _, _ -> },
            showAllChanges = {},
            project = project,
            allFiles = { files },
            loadChunk = 2,
        )
        try {
            wall.show(DiffsWallContent(WallScope(null, "All changes"), files, mapOf("src/F0.kt" to "Calls `src/F4.kt:L2`.")))
            val pane = JScrollPane(wall.card)
            pane.setSize(1400, 900)
            layoutTree(pane)

            assertEquals(listOf("src/F0.kt", "src/F1.kt"), wall.view.blocks.map { it.block.path })
            assertEquals(3, wall.view.remainingFilesCount)

            val block0 = wall.view.blocks.single { it.block.path == "src/F0.kt" }
            clickSummaryToggle(block0)
            assertTrue(block0.showsSummary)
            val summaryPane = block0.summaryTextPane!!
            waitForLinks(summaryPane) { it.renderedLinks.any { l -> l.primaryPath == "src/F4.kt" && l.lines == 2..2 } }

            summaryPane.activateLink(summaryPane.renderedLinks.first { it.primaryPath == "src/F4.kt" && it.lines == 2..2 })
            layoutTree(pane)

            assertEquals(setOf("src/F0.kt", "src/F1.kt", "src/F4.kt"), wall.view.blocks.map { it.block.path }.toSet())
            assertEquals(2, wall.view.remainingFilesCount)
            val loaded4 = wall.view.blocks.single { it.block.path == "src/F4.kt" }
            assertTrue("Loaded target block should become active", loaded4.active)
            assertEquals(setOf(3), loaded4.highlightedRows())
            assertTrue("Base-scope file must not be marked as an external added card",
                loaded4.buildMenu().components.filterIsInstance<JMenuItem>().none { it.text == "Remove from wall" })
        } finally {
            Disposer.dispose(wall)
        }
    }

    private object CodeFileShim {
        fun withAbs(file: dev.insight.codereview.CodeFile, abs: String) =
            dev.insight.codereview.CodeFile(file.path, abs, file.text, file.fingerprint, file.chunks)
    }
}
