package dev.insight.review.wall

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.agent.ToolCallEvent
import dev.insight.codereview.CodeReviewTab
import dev.insight.codereview.codeFile
import dev.insight.codereview.codeSnapshot
import dev.insight.context.ContextPick
import dev.insight.diff.DIFFS_WALL_LABELS
import dev.insight.diff.DiffReviewTab
import dev.insight.diff.DiffsWallContent
import dev.insight.diff.DiffsWallView
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.links.SymbolCandidate
import dev.insight.llm.Completion
import dev.insight.review.ReportLink
import dev.insight.review.ReportTargetKind
import dev.insight.review.SummaryKind
import dev.insight.review.summarize
import dev.insight.review.ui.LinkedTextPane
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.chooseView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private fun paintBlock(block: WallView.BlockView): BufferedImage {
        val image = BufferedImage(block.width.coerceAtLeast(1), block.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            block.paint(g)
        } finally {
            g.dispose()
        }
        return image
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

            layoutTree(scrollPane)
            val blank = Point(summaryPane.width - 3, summaryPane.insets.top / 2)
            val blankOnCard = javax.swing.SwingUtilities.convertPoint(summaryPane, blank, blockA)
            val cardBackground = EditorColorsManager.getInstance().globalScheme.defaultBackground
            assertEquals(cardBackground.rgb, paintBlock(blockA).getRGB(blankOnCard.x, blankOnCard.y))

            fun clickSummary(point: Point) {
                for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                    summaryPane.dispatchEvent(MouseEvent(summaryPane, id, 0L, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1))
                }
            }
            val overviewWidth = blockA.preferredSize.width
            clickSummary(blank)
            PlatformTestUtil.waitWithEventsDispatching("Clicking the summary should zoom to its card", {
                blockA.preferredSize.width > overviewWidth
            }, 5)
            assertTrue(blockA.showsSummary)
            pressKey(surface, "ESCAPE")
            assertEquals(overviewWidth, blockA.preferredSize.width)
            layoutTree(scrollPane)

            // Leading bare L2 resolves via defaultFile to src/A.kt, switches back from summary to diff body, and highlights line 2.
            val text = summaryPane.document.getText(0, summaryPane.document.length)
            val linkOffset = text.indexOf("L2")
            assertTrue(linkOffset >= 0)
            val linkStart = summaryPane.modelToView2D(linkOffset)
            val linkNext = summaryPane.modelToView2D(linkOffset + 1)
            clickSummary(Point(((linkStart.x + linkNext.x) / 2).toInt(), linkStart.centerY.toInt()))
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

    fun `test symbol declared several times in one file opens one card listing its declarations`() {
        val helperText = (1..30).joinToString("\n") { n ->
            when (n) {
                5 -> "fun process(x: Int) = x"
                25 -> "fun process(s: String) = s"
                else -> "// helper line $n"
            }
        } + "\n"
        val aText = "fun alpha() = process(1)\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        myFixture.addFileToProject("src/Helper.kt", helperText)
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", "@@ -1 +1 @@\n-fun alpha() = 0\n+$aText", "a",
            before = "fun alpha() = 0\n", after = aText)

        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(ReviewSnapshot(listOf(diffA), emptyList()))
            val aId = tab.state.summaries.tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/A.kt" }.id
            tab.state.summarize(tab.state.revision, mapOf(aId to "Calls `process()` for each item."))
            tab.update(ReviewSnapshot(listOf(diffA), emptyList()))
            chooseView(tab, "Diffs wall")
            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1400, 900)
            layoutTree(scrollPane)

            val blockA = blocks(tab.canvas).single { it.block.path == "src/A.kt" }
            clickSummaryToggle(blockA)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { pane -> pane.renderedLinks.any { it.symbol?.name == "process" } }

            // Both declarations sit in one file: no chooser, one card, and its declarations listed over it.
            summaryPane.activateLink(summaryPane.renderedLinks.first { it.symbol?.name == "process" })
            PlatformTestUtil.waitWithEventsDispatching("The declarations should be listed on the Helper.kt card", {
                descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().isNotEmpty()
            }, 10)
            val panel = descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().single()
            assertTrue(panel.sites.path.endsWith("Helper.kt"))
            assertEquals(listOf(5, 25), panel.sites.sites.map { it.line })
            assertEquals(5, panel.sites.current)
            val helper = blocks(tab.canvas).single { it.block.path == panel.sites.path }
            assertTrue(helper.active)
            assertEquals(setOf(4), helper.highlightedRows())
            layoutTree(scrollPane)
            assertTrue("The list lies over its card", helper.bounds.contains(panel.bounds))
            assertTrue(panel.isVisible)
            assertTrue("The list stays in front of its card", surface.getComponentZOrder(panel) < surface.getComponentZOrder(helper))

            // Each entry scrolls the card to its declaration without hiding or burying the list.
            descendants(panel).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-link-site" && "Line 25" in it.text }.doClick()
            assertEquals(setOf(24), blocks(tab.canvas).single { it.block.path == panel.sites.path }.highlightedRows())
            assertEquals(25, panel.sites.current)
            assertSame(surface, panel.parent)
            assertTrue(panel.isVisible)
            assertTrue("Clicking an entry keeps the list in front of its card",
                surface.getComponentZOrder(panel) < surface.getComponentZOrder(helper))

            descendants(panel).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-link-site" && "Line 5" in it.text }.doClick()
            assertEquals(setOf(4), blocks(tab.canvas).single { it.block.path == panel.sites.path }.highlightedRows())
            assertEquals(5, panel.sites.current)
            assertTrue(panel.isVisible)
            assertTrue("Repeated clicks keep the list in front of its card",
                surface.getComponentZOrder(panel) < surface.getComponentZOrder(helper))

            // Clicking the card itself keeps the list visible in front of it.
            helper.dispatchEvent(MouseEvent(helper, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK,
                helper.width / 2, helper.height / 2, 1, false, MouseEvent.BUTTON1))
            assertTrue(panel.isVisible)
            assertTrue("Clicking the card keeps the list in front of it",
                surface.getComponentZOrder(panel) < surface.getComponentZOrder(helper))

            // Switching to skim mode preserves the list over the rebuilt card.
            pressKey(surface, "M")
            layoutTree(scrollPane)
            val skimHelper = blocks(tab.canvas).single { it.block.path == panel.sites.path }
            assertSame(surface, panel.parent)
            assertTrue(panel.isVisible)
            assertTrue("Switching to skim mode keeps the list in front of its card",
                surface.getComponentZOrder(panel) < surface.getComponentZOrder(skimHelper))

            descendants(panel).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-link-sites-hide" }.doClick()
            assertTrue(descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().isEmpty())
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

    fun `test preferred visible overload on diff card still lists hidden sibling overload`() {
        val totalLines = WALL_MAX_LINES + 25
        val bBeforeLines = (1..totalLines).map { n ->
            when (n) {
                2 -> "fun overloadFn(x: Int) = 0"
                totalLines - 5 -> "fun overloadFn(s: String) = \"0\""
                else -> "// line $n"
            }
        }
        val bAfterLines = bBeforeLines.mapIndexed { idx, line ->
            when (idx + 1) {
                2 -> "fun overloadFn(x: Int) = x"
                totalLines - 5 -> "fun overloadFn(s: String) = s"
                else -> line
            }
        }
        val bBefore = bBeforeLines.joinToString("\n") + "\n"
        val bAfter = bAfterLines.joinToString("\n") + "\n"
        val hiddenLine = totalLines - 5
        val patchB = buildString {
            val firstHunkCount = WALL_MAX_LINES + 5
            append("@@ -1,$firstHunkCount +1,$firstHunkCount @@\n")
            append(" // line 1\n")
            append("-fun overloadFn(x: Int) = 0\n")
            append("+fun overloadFn(x: Int) = x\n")
            for (n in 3..firstHunkCount) append(" // line $n\n")
            append("@@ -${hiddenLine - 1},3 +${hiddenLine - 1},3 @@\n")
            append(" // line ${hiddenLine - 1}\n")
            append("-fun overloadFn(s: String) = \"0\"\n")
            append("+fun overloadFn(s: String) = s\n")
            append(" // line ${hiddenLine + 1}\n")
        }
        val aText = "fun alpha() = overloadFn(1)\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        val bVf = myFixture.addFileToProject("src/B.kt", bAfter).virtualFile
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", "@@ -1 +1 @@\n-fun alpha() = 0\n+$aText", "a",
            before = "fun alpha() = 0\n", after = aText)
        val diffB = ReviewFile("src/B.kt", bVf.path, null, "modified", patchB, "b",
            before = bBefore, after = bAfter)

        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(ReviewSnapshot(listOf(diffA, diffB), emptyList()))
            val aId = tab.state.summaries.tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/A.kt" }.id
            tab.state.summarize(tab.state.revision, mapOf(aId to "Calls `overloadFn()`."))
            tab.update(ReviewSnapshot(listOf(diffA, diffB), emptyList()))
            chooseView(tab, "Diffs wall")

            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1400, 900)
            layoutTree(scrollPane)

            val blockA = blocks(tab.canvas).single { it.block.path == "src/A.kt" }
            clickSummaryToggle(blockA)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { it.renderedLinks.any { l -> l.symbol?.name == "overloadFn" } }

            // One overload is visible in src/B.kt's initial hunk (line 2), the other sits past WALL_MAX_LINES: both must still appear on B's card.
            summaryPane.activateLink(summaryPane.renderedLinks.first { it.symbol?.name == "overloadFn" })
            PlatformTestUtil.waitWithEventsDispatching("Preferred overload should still list sibling overload on B.kt card", {
                descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().isNotEmpty()
            }, 10)
            val panel = descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().single()
            assertEquals("src/B.kt", panel.sites.path)
            assertEquals(listOf(2, hiddenLine), panel.sites.sites.map { it.line })
            assertEquals(2, panel.sites.current)

            // Clicking the hidden overload expands the diff card to include hiddenLine.
            descendants(panel).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-link-site" && "Line $hiddenLine" in it.text }.doClick()
            val updatedB = blocks(tab.canvas).single { it.block.path == "src/B.kt" }
            assertEquals(hiddenLine, panel.sites.current)
            assertTrue("Hidden overload line should now be highlighted", updatedB.highlightedRows().isNotEmpty())
            assertEquals(hiddenLine, updatedB.block.lines[updatedB.highlightedRows().single()].new)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test canvas chat in skim mode does not cover declaration list`() {
        val helperText = (1..30).joinToString("\n") { n ->
            when (n) {
                5 -> "fun skimProc(x: Int) = x"
                25 -> "fun skimProc(s: String) = s"
                else -> "// helper line $n"
            }
        } + "\n"
        val aText = "fun alpha() = skimProc(1)\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        myFixture.addFileToProject("src/Helper.kt", helperText)
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", "@@ -1 +1 @@\n-fun alpha() = 0\n+$aText", "a",
            before = "fun alpha() = 0\n", after = aText)

        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = {
            Completion("Uses `skimProc()` from helper.")
        })
        try {
            tab.update(ReviewSnapshot(listOf(diffA), emptyList()))
            chooseView(tab, "Diffs wall")
            val surface = surfaceOf(tab.canvas)
            val scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as JScrollPane
            scrollPane.setSize(1200, 800)
            layoutTree(scrollPane)

            pressKey(surface, "M")
            layoutTree(scrollPane)

            descendants(tab.canvas).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-canvas-chat-link" }.doClick()
            val chatPanel = descendants(tab.canvas).filterIsInstance<WallChatPanel>().single()
            chatPanel.send("Where is skimProc?")
            PlatformTestUtil.waitWithEventsDispatching("Chat reply should complete", {
                descendants(chatPanel).filterIsInstance<LinkedTextPane>().any { "skimProc()" in it.markdown }
            }, 10)
            val replyPane = descendants(chatPanel).filterIsInstance<LinkedTextPane>().last { "skimProc()" in it.markdown }
            waitForLinks(replyPane) { it.renderedLinks.any { l -> l.symbol?.name == "skimProc" } }

            replyPane.activateLink(replyPane.renderedLinks.first { it.symbol?.name == "skimProc" })
            PlatformTestUtil.waitWithEventsDispatching("Declaration list should appear without being covered by canvas chat", {
                descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().isNotEmpty()
            }, 10)
            layoutTree(scrollPane)

            val panel = descendants(tab.canvas).filterIsInstance<WallLinkSitesPanel>().single()
            assertTrue(panel.isVisible)
            assertTrue(chatPanel.isVisible)
            assertTrue(panel.width > 0 && panel.height > 0)
            assertFalse("Canvas chat must not cover the link sites panel", panel.bounds.intersects(chatPanel.bounds))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test hidden inline link sites falls back to popup and card menu`() {
        val helperText = (1..30).joinToString("\n") { n ->
            when (n) {
                5 -> "fun tightFn(x: Int) = x"
                25 -> "fun tightFn(s: String) = s"
                else -> "// helper line $n"
            }
        } + "\n"
        val aText = "fun alpha() = tightFn(1)\n"
        val aVf = myFixture.addFileToProject("src/A.kt", aText).virtualFile
        myFixture.addFileToProject("src/Helper.kt", helperText)
        val diffA = ReviewFile("src/A.kt", aVf.path, null, "modified", "@@ -1 +1 @@\n-fun alpha() = 0\n+$aText", "a",
            before = "fun alpha() = 0\n", after = aText)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val chats = WallChats(project, scope, backend = {
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit) = ""
            }
        }, welcome = { "" })
        val wall = DiffsWallView(
            openDiff = { _, _ -> },
            showAllChanges = {},
            chats = chats,
            project = project,
            scope = scope,
            allFiles = { listOf(diffA) },
        )
        try {
            wall.show(DiffsWallContent(WallScope(null, "All changes"), listOf(diffA), mapOf("src/A.kt" to "Calls `tightFn()`.")))
            val scrollPane = JScrollPane(wall.card)
            scrollPane.setSize(500, 400)
            layoutTree(scrollPane)

            val surface = descendants(wall.card).filterIsInstance<WallView.Surface>().single()
            pressKey(surface, "M")
            wall.view.toggleCanvasChat()
            layoutTree(scrollPane)

            var popped: WallLinkSites? = null
            var chooseCallback: ((WallLinkSite) -> Unit)? = null
            wall.view.chooseLinkSite = { sites, _, onChosen ->
                popped = sites
                chooseCallback = onChosen
            }

            val blockA = wall.view.blocks.single { it.block.path == "src/A.kt" }
            clickSummaryToggle(blockA)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { it.renderedLinks.any { l -> l.symbol?.name == "tightFn" } }

            summaryPane.activateLink(summaryPane.renderedLinks.first { it.symbol?.name == "tightFn" })
            PlatformTestUtil.waitWithEventsDispatching("Popup fallback should trigger when inline panel cannot fit", {
                popped != null
            }, 10)
            layoutTree(scrollPane)

            val sites = popped!!
            val panel = wall.view.linkSitesPanel(sites.path)!!
            assertFalse("Inline panel should stay hidden when no unobstructed slot fits", panel.isVisible)
            assertEquals(listOf(5, 25), sites.sites.map { it.line })

            chooseCallback!!(sites.sites.last())
            val helperBlock = wall.view.blocks.single { it.block.path == sites.path }
            assertEquals(setOf(24), helperBlock.highlightedRows())

            // Card "..." menu keeps the declarations reachable as well.
            popped = null
            helperBlock.buildMenu().components.filterIsInstance<JMenuItem>().single { it.text.startsWith("Declarations of tightFn") }.doClick()
            assertNotNull(popped)
            assertEquals(25, popped!!.current)
        } finally {
            Disposer.dispose(wall)
            Disposer.dispose(chats)
            scope.cancel()
        }
    }

    fun `test editor fallback opens declaration chooser when file cannot host a wall card`() {
        val bigText = (1..20).joinToString("\n") { n ->
            when (n) {
                3 -> "fun hugeFn(x: Int) = x"
                10 -> "fun hugeFn(s: String) = s"
                else -> "// line $n"
            }
        } + "\n"
        myFixture.addFileToProject("src/Big.kt", bigText)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val decliningHost = object : WallLinkHost {
            override val isDiffWall: Boolean = true
            override fun scopePaths(): List<String> = listOf("src/A.kt")
            override fun wallPathFor(path: String, absolutePath: String?): String = normalizeWallPath(path)
            override fun ensureCard(path: String, absolutePath: String?, lines: IntRange?): String? = null
            override fun ensureLineLoaded(path: String, targetLine: Int): Boolean = false
            override fun isAddedCard(path: String): Boolean = false
            override fun contextPick(link: ReportLink, path: String, absolutePath: String?): ContextPick? = null
        }
        val wall = WallView(
            DIFFS_WALL_LABELS,
            openLine = { _, _ -> },
            showAll = {},
            chats = null,
            highlighter = null,
            openOnCanvas = null,
            onContext = null,
            summarize = null,
            cardWidthScale = 1.0,
            links = WallLinkSupport(project = project, coroutineScope = scope, linkHost = decliningHost),
        )
        try {
            val editorManager = FileEditorManager.getInstance(project)
            editorManager.openFiles.forEach(editorManager::closeFile)
            wall.show(WallContent(
                WallScope(null, "All"),
                listOf(WallBlock("src/A.kt", "src/A.kt", "", listOf(WallLine.source(1, "fun a() = hugeFn(1)")), 0)),
                mapOf("src/A.kt" to "Calls `hugeFn()`."),
            ))
            val scrollPane = JScrollPane(wall.card)
            scrollPane.setSize(1200, 800)
            layoutTree(scrollPane)

            var chosenName: String? = null
            var chosenCands: List<SymbolCandidate> = emptyList()
            var onChosen: ((SymbolCandidate) -> Unit)? = null
            wall.chooseDeclaration = { name, cands, _, cb ->
                chosenName = name
                chosenCands = cands
                onChosen = cb
            }

            val blockA = wall.blocks.single()
            clickSummaryToggle(blockA)
            val summaryPane = blockA.summaryTextPane!!
            waitForLinks(summaryPane) { it.renderedLinks.any { l -> l.symbol?.name == "hugeFn" } }

            summaryPane.activateLink(summaryPane.renderedLinks.first { it.symbol?.name == "hugeFn" })
            PlatformTestUtil.waitWithEventsDispatching("Editor fallback should ask which declaration to open", {
                onChosen != null
            }, 10)

            assertNull("Editor must not open before choosing an overload", FileEditorManager.getInstance(project).selectedTextEditor)
            assertEquals("hugeFn", chosenName)
            assertEquals(listOf(3, 10), chosenCands.map { it.line }.sorted())

            onChosen!!(chosenCands.maxBy { it.line })
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            assertNotNull("Chosen declaration should open in editor", editor)
            assertEquals("Big.kt", FileEditorManager.getInstance(project).selectedFiles.single().name)
            assertEquals(9, editor!!.caretModel.logicalPosition.line)
        } finally {
            FileEditorManager.getInstance(project).let { mgr -> mgr.openFiles.forEach(mgr::closeFile) }
            Disposer.dispose(wall)
            scope.cancel()
        }
    }

    private object CodeFileShim {
        fun withAbs(file: dev.insight.codereview.CodeFile, abs: String) =
            dev.insight.codereview.CodeFile(file.path, abs, file.text, file.fingerprint, file.chunks)
    }
}
