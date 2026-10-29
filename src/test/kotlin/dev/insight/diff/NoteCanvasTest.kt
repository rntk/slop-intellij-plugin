package dev.insight.diff

import dev.insight.review.SummarySource
import dev.insight.review.SummarySegment
import dev.insight.review.Summary
import dev.insight.review.summarize
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ExplainResult
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewSymbol
import dev.insight.review.ReviewSymbolEvidence
import dev.insight.review.ReviewSymbolLinks
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.topicOutline
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import kotlinx.coroutines.Job

class NoteCanvasTest : BasePlatformTestCase() {
    private val note = ReviewNote(listOf("src/a.kt"), "A short explanation.")

    fun testPartialCardUpdatesPreserveMetricsAndTheirScopeInputs() {
        val files = listOf("src/a.kt", "other/b.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-oldWord\n+newWord\n", "1")
        }
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val topics = ReviewTopics(files).assign(files.first().path, "Product>Feature")
        val summaryCard = SummariesCard(summaries, running = false, canRun = false)
        val topicCard = TopicsCard(topics, summaries, canRun = false)
        val fileCard = FileViewCard(files.first(), null, null, topics, summaries, canRun = false)
        val words = wordChanges(files)
        val explanation = ExplainResult("/explain", "An explanation.", running = false)
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(CanvasState(notes = listOf(note), words = words, wordFiles = files,
                diffOverview = words, semantic = SemanticResult.Counting, explanation = explanation))
            canvas.focusWords()
            val chart = descendants(canvas).filterIsInstance<WordChangeChart>().single()
            val overview = descendants(canvas).filterIsInstance<DiffOverviewChart>().single()
            fun assertRetained() {
                assertSame(chart, descendants(canvas).filterIsInstance<WordChangeChart>().single())
                assertSame(overview, descendants(canvas).filterIsInstance<DiffOverviewChart>().single())
                assertEquals("Word changes", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
                assertTrue(descendants(canvas).any { it.name == "semantic-counting" })
                assertTrue(descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == explanation.text })
                assertTrue((0 until canvas.tabs.tabCount).any { canvas.tabs.getTitleAt(it) == "Note · a.kt" })
            }
            // Exercise mount, in-place update and removal for every partial-update entry point.
            canvas.updateTopics(topicCard)
            assertRetained()
            canvas.updateTopics(topicCard.copy(canRun = true))
            assertRetained()
            canvas.updateTopics(null)
            assertRetained()
            canvas.updateSummaries(summaryCard)
            assertRetained()
            canvas.updateSummaries(summaryCard.copy(canRun = true))
            assertRetained()
            canvas.updateSummaries(null)
            assertRetained()
            canvas.updateFileView(fileCard)
            assertRetained()
            canvas.updateFileView(fileCard.copy(error = "Analysis failed."))
            assertRetained()
            canvas.updateFileView(null)
            assertRetained()

            // The charts' backing files must survive too, so scope selection still counts the intended file.
            canvas.chooseWordScope(wordScopes(files).single { it.path == files.first().path })
            val scoped = wordChanges(files.take(1))
            assertEquals(scoped, descendants(canvas).filterIsInstance<WordChangeChart>().single().changes)
            assertEquals(scoped, descendants(canvas).filterIsInstance<DiffOverviewChart>().single().changes)
            canvas.updateSummaries(summaryCard)
            assertEquals(scoped, descendants(canvas).filterIsInstance<WordChangeChart>().single().changes)
            canvas.showNotes(CanvasState(notes = listOf(note)))
            assertEquals(1, canvas.tabs.tabCount)
            assertTrue(descendants(canvas).none { it is WordChangeChart || it is DiffOverviewChart || it.name == "semantic-counting" })
        } finally { canvas.dispose() }
    }

    fun testOpeningAndClosingSummarySearchTabsPreservesMetricCards() {
        val file = ReviewFile("src/a.kt", "/repo/src/a.kt", null, "modified", "@@ -1 +1 @@\n-oldWord\n+newWord\n", "1")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Updates important behavior." })
        val words = wordChanges(listOf(file))
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions()).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.OUTLINE
        }
        try {
            canvas.showNotes(CanvasState(summaries = SummariesCard(summaries, false, true),
                words = words, wordFiles = listOf(file), diffOverview = words, semantic = SemanticResult.Counting))
            canvas.focusSummaries()
            layoutTree(canvas)
            val chart = descendants(canvas).filterIsInstance<WordChangeChart>().single()
            val overview = descendants(canvas).filterIsInstance<DiffOverviewChart>().single()
            val row = descendants(canvas).single { it.name == "summary:root" } as Container
            val area = descendants(row).filterIsInstance<javax.swing.text.JTextComponent>().first()
            area.select(0, "Updates".length)
            selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>().single { it.name == "search-tab" }.doClick()
            assertEquals(5, canvas.tabs.tabCount)
            assertTrue(canvas.tabs.getTitleAt(canvas.tabs.selectedIndex).startsWith("Search"))
            assertSame(chart, descendants(canvas).filterIsInstance<WordChangeChart>().single())
            assertSame(overview, descendants(canvas).filterIsInstance<DiffOverviewChart>().single())
            assertTrue(descendants(canvas).any { it.name == "semantic-counting" })
            (descendants(canvas).single { it.name == "close-search-tab" } as com.intellij.ui.InplaceButton).doClick()
            assertEquals(4, canvas.tabs.tabCount)
            assertSame(chart, descendants(canvas).filterIsInstance<WordChangeChart>().single())
            assertSame(overview, descendants(canvas).filterIsInstance<DiffOverviewChart>().single())
            assertTrue(descendants(canvas).any { it.name == "semantic-counting" })
            canvas.chooseWordScope(wordScopes(listOf(file)).single { it.path == file.path })
            assertEquals(words, descendants(canvas).filterIsInstance<WordChangeChart>().single().changes)
        } finally { canvas.dispose() }
    }

    fun testSummaryWordSizesUseChildFrequenciesInEveryView() = ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.forEach { mode ->
        val tree = buildSummaryTree(listOf("a.kt", "b.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
        })
        val summary = "Token cache and new; Token."
        val leafSummary = "token TOKEN token cache " + "and ".repeat(50)
        val summaries = ReviewSummaries(tree, mapOf(0 to summary,
            tree.root.children[0] to leafSummary, tree.root.children[1] to "Token and"))
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900); summariesView.mode = mode }
        fun area(): WrappedSummaryText = descendants(canvas).filterIsInstance<WrappedSummaryText>().single { it.text == summary }
        fun size(word: String): Int = area().let { it.styledDocument.getFont(it.styledDocument.getCharacterElement(it.text.indexOf(word)).attributes).size }
        fun cloud() = descendants(canvas).single { it.name == "summaries-tags-cloud" } as javax.swing.JCheckBox
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            if (mode == ReviewSummariesMode.HIERARCHY) {
                val toggle = descendants(canvas).single { it.name == "toggle:root" }
                toggle.dispatchEvent(MouseEvent(toggle, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                layoutTree(canvas)
            }
            if (mode == ReviewSummariesMode.DOCUMENT) {
                val rail = descendants(canvas).single { it.name == "node:root" }
                rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                layoutTree(canvas)
            }
            assertEquals(summary, area().text)
            assertFalse("$mode starts with normal text", cloud().isSelected)
            assertEquals(size("Token"), size("cache"))
            assertEquals(size("Token"), size("new"))
            val expanded = canvas.summariesView.expanded.toSet()
            val selected = canvas.summariesView.selected
            cloud().doClick()
            layoutTree(canvas)
            assertTrue(cloud().isSelected)
            assertTrue(canvas.summariesView.tagsCloud)
            assertEquals(expanded, canvas.summariesView.expanded)
            assertEquals(selected, canvas.summariesView.selected)
            assertTrue("$mode enlarges frequently repeated words", size("Token") > size("cache"))
            assertTrue("$mode leaves unsupported words at their base size", size("cache") > size("new"))
            assertEquals("$mode keeps filler words at normal size", size("new"), size("and"))
            val document = area().styledDocument
            assertEquals(size("Token"), document.getFont(document.getCharacterElement(summary.lastIndexOf("Token")).attributes).size)
            area().select(summary.indexOf("cache"), summary.indexOf("cache") + 5)
            assertEquals("cache", area().selectedText)
            assertWordFitsOnOneLine(area(), "Token")
            assertWordFitsOnOneLine(area(), "cache")
            // A leaf has no child summaries to support larger words.
            if (mode != ReviewSummariesMode.HIERARCHY) {
                if (mode == ReviewSummariesMode.EXPLORER) clickSummary(canvas)
                val leaf = descendants(canvas).filterIsInstance<WrappedSummaryText>().first { it.text == leafSummary }
                assertEquals(leaf.font.size, leaf.styledDocument.getFont(leaf.styledDocument.getCharacterElement(0).attributes).size)
                if (mode == ReviewSummariesMode.EXPLORER)
                    (descendants(canvas).single { it.name == "summary-explorer-back" } as javax.swing.JButton).doClick()
            }
            assertTrue(canvas.summariesView.fork().tagsCloud)
            cloud().doClick()
            layoutTree(canvas)
            assertFalse(cloud().isSelected)
            assertFalse(canvas.summariesView.tagsCloud)
            assertFalse(canvas.summariesView.fork().tagsCloud)
            assertEquals(size("Token"), size("cache"))
            assertEquals(size("Token"), size("new"))
            assertEquals(summary, area().text)
            assertEquals(expanded, canvas.summariesView.expanded)
            assertEquals(selected, canvas.summariesView.selected)
        } finally { canvas.dispose() }
    }

    fun testTabsKeepSelectionAcrossUpdatesAndFocusSelectsTheRequestedBlock() {
        val other = ReviewNote(listOf("src/b.kt"), "Other note")
        val canvas = canvas()
        try {
            canvas.showNotes(CanvasState(listOf(note, other)))
            assertEquals(2, canvas.tabs.tabCount)
            canvas.focusNote(other)
            assertEquals(1, canvas.tabs.selectedIndex)
            canvas.showNotes(CanvasState(listOf(note.copy(text = "Updated"), other)))
            assertEquals(1, canvas.tabs.selectedIndex)
            canvas.focusNote(note)
            assertEquals(0, canvas.tabs.selectedIndex)
            canvas.showNotes(CanvasState(listOf(other)))
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals("Note · b.kt", canvas.tabs.getTitleAt(0))
            assertTrue(descendants(canvas).none { it.name == "Resize block" })
        } finally { canvas.dispose() }
    }

    fun testWideTabsScrollHorizontallyAndRefreshPreservesReadingPosition() {
        val canvas = canvas().apply { setSize(300, 300) }
        val longNote = note.copy(text = "Long explanation. ".repeat(2000))
        try {
            canvas.showNotes(CanvasState(listOf(longNote)))
            layoutTree(canvas)
            val page = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            assertTrue(page.horizontalScrollBar.maximum > page.horizontalScrollBar.visibleAmount)
            page.horizontalScrollBar.value = 60
            val position = Point(page.viewport.viewPosition)
            canvas.showNotes(CanvasState(listOf(longNote.copy(text = longNote.text + "Updated."))))
            layoutTree(canvas)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val refreshed = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            assertEquals(position, refreshed.viewport.viewPosition)
        } finally { canvas.dispose() }
    }

    fun testWheelScrollsLongNotesWithoutChangingFontSize() {
        val canvas = canvas()
        try {
            canvas.showNotes(CanvasState(listOf(note.copy(text = "Long explanation. ".repeat(2000)))))
            layoutTree(canvas)
            val text = descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().single()
            val font = text.font
            val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, text) as javax.swing.JScrollPane
            assertTrue(scroll.verticalScrollBar.maximum > scroll.verticalScrollBar.visibleAmount)
            val before = scroll.verticalScrollBar.value
            scroll.dispatchEvent(java.awt.event.MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1, 0,
                20, 20, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 3))
            assertTrue(scroll.verticalScrollBar.value > before)
            assertEquals(font, text.font)
            assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, scroll.horizontalScrollBarPolicy)
        } finally { canvas.dispose() }
    }

    fun testDocumentZoomChangesGeometryAndKeepsCardTextReadable() {
        val file = ReviewFile("src/feature.kt", "/repo/src/feature.kt", null, "modified",
            "@@ -1 +1 @@\n-old\n+new\n", "1")
        val topics = ReviewTopics(listOf(file)).assign(file.path, "Product>Feature")
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, ReviewSummaries(buildSummaryTree(listOf(file))), canRun = false)))
            layoutTree(canvas)
            fun document() = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            fun title() = descendants(document()).filterIsInstance<javax.swing.text.JTextComponent>().first { it.text == "Feature" }
            val originalWidth = document().preferredSize.width
            val originalFont = title().font.size2D
            val originalRailWidth = document().components.single { it.name == "topic:1:product > feature" }.width
            val originalSummaryWidth = document().components.single { it.name == "summary:src/feature.kt" }.width
            val originalDiffWidth = document().components.single { it.name == "file:src/feature.kt" }.width
            (descendants(canvas).first { it.name == "document-zoom-out" } as javax.swing.JButton).doClick()
            layoutTree(canvas)
            assertTrue(document().preferredSize.width < originalWidth)
            assertTrue(title().font.size2D >= originalFont)
            assertTrue(document().components.single { it.name == "topic:1:product > feature" }.width > originalRailWidth)
            assertTrue(document().components.single { it.name == "summary:src/feature.kt" }.width > originalSummaryWidth)
            assertTrue(document().components.single { it.name == "file:src/feature.kt" }.width < originalDiffWidth)
            assertEquals("83%", (descendants(canvas).first { it.name == "document-zoom-level" } as JLabel).text)
            (descendants(canvas).first { it.name == "document-zoom-reset" } as javax.swing.JButton).doClick()
            layoutTree(canvas)
            assertEquals(originalWidth, document().preferredSize.width)
            val title = title()
            title.dispatchEvent(java.awt.event.MouseWheelEvent(title, MouseEvent.MOUSE_WHEEL, 1,
                java.awt.event.InputEvent.CTRL_DOWN_MASK, 5, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1))
            assertEquals("115%", (descendants(canvas).first { it.name == "document-zoom-level" } as JLabel).text)
        } finally { canvas.dispose() }
    }

    fun testTopicAndSummarySearchFieldsFilterDocumentAndHierarchy() {
        val files = listOf("src/wallet.kt", "src/cart.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val topics = ReviewTopics(files).assign(files[0].path, "Storage>Wallet").assign(files[1].path, "UI>Checkout")
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }
            .associate { it.id to if (it.path == files[0].path) "Persists balances" else "Renders the basket" })
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = false)))
            fun field(name: String) = descendants(canvas).single { it.name == name } as com.intellij.ui.SearchTextField
            fun shownFiles() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet()
            val topicField = field("topics-topic-search")
            topicField.text = "checkout"
            layoutTree(canvas)
            assertEquals(setOf(files[1].path), shownFiles())
            assertSame("The search field survives the rebuilt card", topicField, field("topics-topic-search"))
            field("topics-summary-search").text = "balances"
            layoutTree(canvas)
            assertEquals(1, descendants(canvas).count { it.name == "topics-no-matches" })
            topicField.text = ""
            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.text == "Hierarchy" }.doClick()
            layoutTree(canvas)
            val hierarchy = descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().single()
            assertEquals(listOf(files[0].path), hierarchy.outline.files.map { it.path })
            assertEquals("balances", field("topics-summary-search").text)
        } finally { canvas.dispose() }
    }

    fun testDocumentZoomReachesTenPercentWithButtonsAndWheel() {
        val file = ReviewFile("src/feature.kt", "/repo/src/feature.kt", null, "modified",
            "@@ -1 +1 @@\n-old\n+new\n", "1")
        val topics = ReviewTopics(listOf(file)).assign(file.path, "Product>Feature")
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, ReviewSummaries(buildSummaryTree(listOf(file))), canRun = false)))
            fun button(name: String) = descendants(canvas).single { it.name == name } as javax.swing.JButton
            fun percentage() = (descendants(canvas).single { it.name == "document-zoom-level" } as JLabel).text
            repeat(14) { button("document-zoom-out").doClick() }
            layoutTree(canvas)
            assertEquals("10%", percentage())
            assertFalse(button("document-zoom-out").isEnabled)
            assertTrue(descendants(canvas).any { it is PatchView })
            button("document-zoom-reset").doClick()
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            panel.dispatchEvent(java.awt.event.MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, 1,
                java.awt.event.InputEvent.CTRL_DOWN_MASK, 5, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 100))
            assertEquals("10%", percentage())
            button("document-zoom-in").doClick()
            assertEquals("12%", percentage())
        } finally { canvas.dispose() }
    }

    fun testDocumentLevelSwitchersLimitRailsAndKeepDiffsVisible() {
        val files = listOf("src/feature/one.kt", "src/feature/two.kt", "docs/readme.md", "src/other.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val topics = ReviewTopics(files).assign(files[0].path, "Product>Feature>One")
            .assign(files[1].path, "Product>Feature>Two")
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val canvas = canvas().apply { setSize(1600, 700); summariesView.mode = ReviewSummariesMode.DOCUMENT }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = false),
                summaries = SummariesCard(summaries, running = false, canRun = false)))
            fun topicLevel() = descendants(canvas).single { it.name == "topic-level-selector" } as javax.swing.JComboBox<*>
            fun topicPanel() = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            fun folderPanel() = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
            fun summaryLevel(value: Int) {
                (descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>).selectedItem = value
            }
            topicLevel().selectedItem = "Files"
            layoutTree(canvas)
            val topicWidth = topicPanel().preferredSize.width
            val folderWidth = folderPanel().preferredSize.width
            topicLevel().selectedIndex = 0
            summaryLevel(1)
            layoutTree(canvas)
            assertEquals(0, topicLevel().selectedIndex)
            assertEquals(1, canvas.summariesView.shownLevel(summaries.tree))
            assertEquals(setOf("topic:0:product", "topic:pending"), topicPanel().components.mapNotNull { it.name }.filter { it.startsWith("topic:") }.toSet())
            assertEquals(setOf("node:root", "node:directory:docs", "node:directory:src"),
                folderPanel().components.mapNotNull { it.name }.filter { it.startsWith("node:") }.toSet())
            assertTrue(topicPanel().preferredSize.width < topicWidth)
            assertTrue(folderPanel().preferredSize.width < folderWidth)
            for (panel in listOf(topicPanel(), folderPanel())) {
                assertEquals(files.size, descendants(panel).filterIsInstance<PatchView>().size)
            }
            topicLevel().selectedItem = "Files"
            summaryLevel(ChangeSummariesViewState.branchLevels(summaries.tree))
            layoutTree(canvas)
            assertEquals(topicWidth, topicPanel().preferredSize.width)
            assertEquals(folderWidth, folderPanel().preferredSize.width)
        } finally { canvas.dispose() }
    }

    fun testDocumentCardsExpandForLongLabelsAndSummaryWords() {
        val label = "AuthenticationSynchronizationPipelineCoordinatorRegistry"
        // Exceeds the old 720px cap, which forced word wrapping despite the measured word width.
        val longWord = label.repeat(3)
        val file = ReviewFile("src/feature.kt", "/repo/src/feature.kt", null, "modified",
            "@@ -1 +1 @@\n-old\n+new\n", "1")
        val topics = ReviewTopics(listOf(file)).assign(file.path, "Product>$label")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == file.path }.id to longWord))
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = false)))
            repeat(4) { (descendants(canvas).first { it.name == "document-zoom-out" } as javax.swing.JButton).doClick() }
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            val rail = panel.components.single { it.name == "topic:1:product > ${label.lowercase()}" }
            val summary = panel.components.single { it.name == "summary:src/feature.kt" }
            val title = descendants(rail as Container).filterIsInstance<javax.swing.text.JTextComponent>().single { it.text == label }
            val summaryText = descendants(summary as Container).filterIsInstance<javax.swing.text.JTextComponent>().single { it.text == longWord }
            assertTrue(rail.width > com.intellij.util.ui.JBUI.scale(DocumentColumnsPanel.RAIL_CARD_WIDTH))
            assertTrue(summary.width > com.intellij.util.ui.JBUI.scale(DocumentColumnsPanel.SUMMARY_WIDTH))
            assertTrue(title.getFontMetrics(title.font).stringWidth(label) <= title.width)
            assertTrue(summaryText.getFontMetrics(summaryText.font).stringWidth(longWord) <= summaryText.width)
            assertWordFitsOnOneLine(title, label)
            assertWordFitsOnOneLine(summaryText, longWord)
            assertTrue(rail.height >= (rail as JComponent).preferredSize.height)
            rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
            val selectedSummary = panel.components.single { it.name?.startsWith("card-summary:") == true } as Container
            assertWordFitsOnOneLine(descendants(selectedSummary).filterIsInstance<javax.swing.text.JTextComponent>()
                .single { it.text == longWord }, longWord)
        } finally { canvas.dispose() }
    }

    fun testFolderDocumentCardWidthsFollowFontGrowthAndRenderWholeWords() {
        val folder = "AuthenticationSynchronizationPipelineCoordinatorRegistryConfiguration"
        val word = folder.repeat(3)
        val sentence = "Updates $word consistently."
        val files = listOf("$folder/feature.kt", "$folder/extra.kt", "other.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val folderNode = tree.nodes.single { it.kind == SummaryKind.DIRECTORY }
        val summaries = ReviewSummaries(tree).summarize(
            tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to sentence } + (folderNode.id to sentence))
        val canvas = canvas().apply { setSize(1200, 700); summariesView.mode = ReviewSummariesMode.DOCUMENT }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = false)))
            fun document() = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
            fun rail() = document().components.single { it.name == "node:${folderNode.identity}" } as Container
            fun summary() = document().components.single { it.name == "summary:${files.first().path}" } as Container
            layoutTree(canvas)
            val initialRailWidth = rail().width
            val initialSummaryWidth = summary().width
            var railWidth = initialRailWidth
            var summaryWidth = initialSummaryWidth
            repeat(4) {
                (descendants(canvas).single { it.name == "document-zoom-out" } as javax.swing.JButton).doClick()
                layoutTree(canvas)
                // Glyph measurements can remain equal between fractional font sizes.
                assertTrue("Folder card must not shrink as its font grows", rail().width >= railWidth)
                assertTrue("Summary card must not shrink as its font grows", summary().width >= summaryWidth)
                railWidth = rail().width
                summaryWidth = summary().width
                assertWordFitsOnOneLine(descendants(rail()).filterIsInstance<javax.swing.text.JTextComponent>().single(), "$folder/")
                assertWordFitsOnOneLine(descendants(summary()).filterIsInstance<javax.swing.text.JTextComponent>().single(), word)
            }
            assertTrue(railWidth > initialRailWidth)
            assertTrue(summaryWidth > initialSummaryWidth)
            val rail = rail()
            rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
            val selectedSummary = document().components.single { it.name == "card-summary:${folderNode.identity}" } as Container
            assertWordFitsOnOneLine(descendants(selectedSummary).filterIsInstance<javax.swing.text.JTextComponent>()
                .single { it.text == sentence }, word)
        } finally { canvas.dispose() }
    }

    fun testTopicsDocumentAlignsRailCardsAndSummariesWithFileDiffs() {
        val files = listOf("src/wallet.kt", "src/auth.kt", "ui/form.kt", "docs/new.md").mapIndexed { index, path ->
            ReviewFile(path, "/repo/$path", null, "modified",
                "@@ -1,2 +1,${2 + index * 6} @@\n ctx\n-old\n" + "+new\n".repeat(1 + index * 6), "1")
        }
        val topics = ReviewTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == "src/auth.kt" }.id to "Adds token refresh."))
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = true)))
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            fun named(name: String) = panel.components.single { it.name == name }
            // Topic order, not path order; the file without a topic comes last.
            assertEquals(listOf("src/auth.kt", "src/wallet.kt", "ui/form.kt", "docs/new.md"), panel.outline.files.map { it.path })
            val sections = panel.outline.files.map { named("file:${it.path}") }
            for (node in panel.outline.nodes) {
                val card = named("topic:${node.level}:${node.identity}")
                assertEquals(node.fullPath, sections[node.first].y, card.y)
                assertEquals(node.fullPath, sections[node.last].y + sections[node.last].height, card.y + card.height)
                assertTrue("Rail sits right of the document", card.x >= sections[0].x + sections[0].width)
            }
            // Broad topics are outermost; the leaf level sits next to the diffs.
            assertTrue(named("topic:0:storage").x > named("topic:1:storage > user").x)
            assertTrue(named("topic:1:storage > user").x > named("topic:2:storage > user > auth").x)
            assertEquals(sections[3].y, named("topic:pending").y)
            // Summaries come from the summary tree and start level with their file's diff, left of it.
            panel.outline.files.forEachIndexed { index, file ->
                val note = named("summary:${file.path}")
                assertEquals(sections[index].y, note.y)
                assertTrue(note.x + note.width <= sections[index].x)
                assertTrue(sections[index].height >= note.height)
            }
            // A summary follows the viewport down its section, but never past the section's end.
            val column = named("summary:src/wallet.kt") as Container
            val text = column.getComponent(0)
            panel.updateStickyHeaders(column.y + 5)
            assertEquals(5, text.y)
            panel.updateStickyHeaders(column.y + column.height + 100)
            assertEquals(column.height - text.height, text.y)
            panel.updateStickyHeaders(0)
            assertEquals(0, text.y)
            val texts = descendants(panel).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
            assertTrue(texts.contains("Adds token refresh."))
            assertEquals(3, texts.count { it == "No summary yet" })
            assertEquals(listOf("hunk - - @@ -1,2 +1,2 @@", "context 1 1  ctx", "removed 2 - -old", "added - 2 +new"),
                descendants(sections[1] as Container).filterIsInstance<PatchView>().single().describe().take(4))

            // Selecting a topic outlines its files and survives summary updates.
            val auth = named("topic:2:storage > user > auth") as JComponent
            auth.dispatchEvent(MouseEvent(auth, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            assertTrue(canvas.topicsView.isSelected(panel.outline.nodes.single { it.identity == "storage > user > auth" }))
            // The selected topic's summary replaces its files' notes in the margin, level with its card.
            val authSummary = named("card-summary:2:storage > user > auth") as Container
            assertEquals(auth.y, authSummary.y)
            assertTrue(authSummary.x + authSummary.width <= sections[1].x)
            assertFalse(named("summary:src/auth.kt").isVisible)
            assertTrue(named("summary:src/wallet.kt").isVisible)
            assertTrue(descendants(authSummary).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == "Adds token refresh." })
            // Hovering another card shows its summary instead; leaving it brings the selection's back.
            val storage = named("topic:0:storage") as JComponent
            storage.dispatchEvent(MouseEvent(storage, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false))
            val storageSummary = named("card-summary:0:storage") as Container
            assertEquals(storage.y, storageSummary.y)
            assertTrue(panel.components.none { it.name == "card-summary:2:storage > user > auth" })
            assertTrue(listOf("src/auth.kt", "src/wallet.kt").none { named("summary:$it").isVisible })
            assertTrue(descendants(storageSummary).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == "No summary yet" })
            storage.dispatchEvent(MouseEvent(storage, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false))
            assertTrue(panel.components.none { it.name == "card-summary:0:storage" })
            assertTrue(named("summary:src/wallet.kt").isVisible)
            named("card-summary:2:storage > user > auth")
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(tree.nodes.single { it.path == "ui/form.kt" }.id to "Validates the card number."))
            canvas.updateTopics(TopicsCard(topics, more, canRun = false))
            assertEquals(bounds, canvas.components.single().bounds)
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == "Validates the card number." })
            assertEquals("storage > user > auth", canvas.topicsView.selected?.substringAfter(':'))
            descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "auth.kt" }.doClick()
            assertEquals(listOf("src/auth.kt"), opened)
            canvas.updateTopics(null)
            assertTrue(descendants(canvas).none { it is ChangeTopicsPanel })
        } finally { canvas.dispose() }
    }

    fun testPatchViewNumbersLinesAndBoundsLongPatches() {
        val view = PatchView("Binary file\n@@ -10,2 +10,3 @@\n a\n-b\n+c\n+d\n[After: no newline at end of file]\n", 400, 1.0)
        assertEquals(listOf("note - - Binary file", "hunk - - @@ -10,2 +10,3 @@", "context 10 10  a", "removed 11 - -b",
            "added - 11 +c", "added - 12 +d", "note - - [After: no newline at end of file]"), view.describe())
        val long = PatchView("@@ -1,0 +1,900 @@\n" + "+x\n".repeat(900), 400, 1.0)
        assertEquals(PatchView.MAX_LINES, long.lineCount)
        assertEquals(901 - PatchView.MAX_LINES, long.hiddenLines)
    }

    fun testDocumentDiffScrollsInsideWiderFileBlock() {
        val file = ReviewFile("src/wide.kt", "/repo/src/wide.kt", null, "modified",
            "@@ -1,0 +1,80 @@\n" + ("+" + "wide content ".repeat(100) + "\n").repeat(80), "1")
        val narrow = ReviewFile("src/narrow.kt", "/repo/src/narrow.kt", null, "modified",
            "@@ -1,0 +1,80 @@\n" + "+short line\n".repeat(80), "1")
        val files = listOf(file, narrow)
        val topics = files.fold(ReviewTopics(files)) { result, entry -> result.assign(entry.path, "Product>Feature") }
        val canvas = NoteCanvas({}, {}).apply { setSize(1100, 700) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics,
                ReviewSummaries(buildSummaryTree(files)), canRun = false)))
            layoutTree(canvas)
            val document = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            val section = document.components.single { it.name == "file:${file.path}" }
            assertTrue("The document reserves roughly twice the old 620px content width",
                section.width >= com.intellij.util.ui.JBUI.scale(1100))
            val scroll = descendants(section as Container).single { it.name == "document-source-scroll" } as javax.swing.JScrollPane
            assertTrue(scroll.height <= com.intellij.util.ui.JBUI.scale(420))
            assertTrue(scroll.verticalScrollBar.isVisible)
            assertTrue(scroll.horizontalScrollBar.isVisible)
            val documentPosition = document.location
            scroll.verticalScrollBar.value = 100
            scroll.horizontalScrollBar.value = 200
            assertEquals(100, scroll.viewport.viewPosition.y)
            assertEquals(200, scroll.viewport.viewPosition.x)
            assertEquals(documentPosition, document.location)
            val narrowSection = document.components.single { it.name == "file:${narrow.path}" } as Container
            val narrowScroll = descendants(narrowSection).single { it.name == "document-source-scroll" } as javax.swing.JScrollPane
            assertTrue(narrowScroll.verticalScrollBar.isVisible)
            assertFalse(narrowScroll.horizontalScrollBar.isVisible)
        } finally { canvas.dispose() }
    }

    fun testTopicHeadingsStayInViewWhileLongCardsScroll() {
        val files = (1..24).map { index ->
            val path = "src/file$index.kt"
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n" + "+line\n".repeat(30), "1")
        }
        val topics = files.fold(ReviewTopics(files)) { result, file -> result.assign(file.path, "Storage>User") }
        val canvas = NoteCanvas({}, {}).apply { setSize(1100, 400) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, ReviewSummaries(buildSummaryTree(files)), canRun = false)))
            layoutTree(canvas)
            val document = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            val documentScroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, document) as javax.swing.JScrollPane
            val file = document.components.single { it.name == "file:src/file1.kt" } as Container
            val fileHeading = file.components.first()
            val topic = document.components.single { it.name == "topic:0:storage" } as Container
            val topicHeading = topic.components.first()
            val outer = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            // Scrolls the card just past the component's top, whatever the status lines above it take.
            fun scrollPast(component: Component) {
                outer.verticalScrollBar.value = javax.swing.SwingUtilities.convertPoint(component, 0, 0, outer.viewport.view).y + 30
            }
            scrollPast(file)
            assertTrue(fileHeading.y > file.insets.top)
            assertTrue(topicHeading.y > topic.insets.top)
            assertTrue(javax.swing.SwingUtilities.convertPoint(fileHeading, 0, 0, outer.viewport).y >= 0)
            assertTrue(javax.swing.SwingUtilities.convertPoint(topicHeading, 0, 0, outer.viewport).y >= 0)
            // A layout pass between scrolls keeps the headings where the scroll put them.
            val stuck = fileHeading.y to topicHeading.y
            file.doLayout(); topic.doLayout()
            assertEquals(stuck, fileHeading.y to topicHeading.y)
            outer.verticalScrollBar.value = 0
            documentScroll.verticalScrollBar.value = 120
            assertTrue(fileHeading.y > file.insets.top)
            assertTrue(topicHeading.y > topic.insets.top)
            assertTrue(fileHeading.y + fileHeading.height <= file.height - file.insets.bottom)

            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.text == "Hierarchy" }.doClick()
            layoutTree(canvas)
            val hierarchy = descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().single()
            val hierarchyScroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, hierarchy) as javax.swing.JScrollPane
            val branch = hierarchy.components.single { it.name == "topic:0:storage" } as Container
            val branchHeading = branch.components.first()
            scrollPast(branch)
            assertTrue(branchHeading.y > branch.insets.top)
            assertTrue(javax.swing.SwingUtilities.convertPoint(branchHeading, 0, 0, outer.viewport).y >= 0)
            outer.verticalScrollBar.value = 0
            hierarchyScroll.verticalScrollBar.value = 120
            assertTrue(branchHeading.y > branch.insets.top)
            assertTrue(branchHeading.y + branchHeading.height <= branch.height - branch.insets.bottom)
        } finally { canvas.dispose() }
    }

    fun testSummaryHierarchyFolderHeadingStaysInView() {
        val files = (1..24).map { index ->
            val path = "src/file$index.kt"
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val canvas = NoteCanvas({}, {}).apply { setSize(1100, 400) }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), running = false, canRun = false)))
            canvas.summariesView.selectLevel(ChangeSummariesViewState.branchLevels(tree), tree)
            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:hierarchy" }.doClick()
            layoutTree(canvas)
            val hierarchy = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
            val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, hierarchy) as javax.swing.JScrollPane
            val root = hierarchy.components.single { it.name == "node:${tree.root.identity}" } as Container
            val heading = root.components.first()
            val outer = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            // Scroll past the controls and into the folder, regardless of the header's height.
            outer.verticalScrollBar.value = javax.swing.SwingUtilities.convertPoint(root, 0, 0, outer.viewport).y + 120
            assertTrue(heading.y > root.insets.top)
            assertTrue(javax.swing.SwingUtilities.convertPoint(heading, 0, 0, outer.viewport).y >= 0)
            outer.verticalScrollBar.value = 0
            scroll.verticalScrollBar.value = 120
            assertTrue(heading.y > root.insets.top)
            assertTrue(heading.y + heading.height <= root.height - root.insets.bottom)
        } finally { canvas.dispose() }
    }

    fun testTopicsHierarchySpansChildRowsWithSummariesBesideFilesAndFolds() {
        val files = listOf("src/wallet.kt", "src/auth.kt", "ui/form.kt", "docs/new.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val topics = ReviewTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == "src/auth.kt" }.id to "Adds token refresh."))
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().single()
        fun names() = panel().components.mapNotNull { it.name }
        fun named(name: String) = panel().components.single { it.name == name }
        fun bottom(component: Component) = component.y + component.height
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = true)))
            layoutTree(canvas)
            // The document stays the default; the hierarchy is a second mode of the same card.
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeTopicsPanel>().size)
            fun mode(text: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.text == text }
            mode("Hierarchy").doClick(); layoutTree(canvas)
            assertTrue(descendants(canvas).none { it is ChangeTopicsPanel })
            // Broad topics in the first column; each file is a leaf one column right of its deepest topic.
            val storage = named("topic:0:storage")
            val user = named("topic:1:storage > user")
            val auth = named("topic:2:storage > user > auth")
            val authFile = named("file:src/auth.kt")
            val walletFile = named("file:src/wallet.kt")
            assertTrue(storage.x < user.x && user.x < auth.x && auth.x < authFile.x)
            assertEquals(auth.x, named("file:ui/form.kt").x)
            assertEquals(setOf(storage.width), names().filter { it.startsWith("topic:") || it.startsWith("file:") }.map { named(it).width }.toSet())
            // Every topic spans exactly the rows beneath it.
            for (topic in listOf(storage, user)) {
                assertEquals(authFile.y, topic.y)
                assertEquals(bottom(walletFile), bottom(topic))
            }
            assertEquals(authFile.y, auth.y)
            assertEquals(bottom(authFile), bottom(auth))
            assertEquals(named("file:ui/form.kt").y, named("topic:0:ui").y)
            // Files without a topic come last, under a placeholder.
            val pending = named("topic:pending")
            assertTrue(pending.y >= bottom(named("topic:0:ui")))
            assertEquals(named("file:docs/new.md").y, pending.y)
            // Summaries come from the summary tree and start right of their file, level with it.
            for (file in files) {
                val note = named("summary:${file.path}")
                val leaf = named("file:${file.path}")
                assertEquals(leaf.y, note.y)
                assertTrue(note.x >= leaf.x + leaf.width)
                assertTrue(leaf.height >= note.height)
            }
            val texts = descendants(panel()).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
            assertTrue(texts.contains("Adds token refresh."))
            assertEquals(3, texts.count { it == "No summary yet" })

            // A folded topic takes one row and shows the summary of exactly its files, else their names.
            click(descendants(panel()).single { it.name == "toggle:2:storage > user > auth" })
            assertFalse("file:src/auth.kt" in names())
            assertEquals("Adds token refresh.", (named("summary:2:storage > user > auth") as javax.swing.text.JTextComponent).text)
            click(descendants(panel()).single { it.name == "toggle:1:storage > user" })
            assertEquals("auth.kt, wallet.kt", (named("summary:1:storage > user") as javax.swing.text.JTextComponent).text)
            assertEquals(named("topic:1:storage > user").height, named("topic:0:storage").height)

            // Level 1 folds every top-level topic; Files shows every level again.
            fun level() = descendants(canvas).single { it.name == "topic-level-selector" } as javax.swing.JComboBox<*>
            assertEquals(listOf("1", "2", "3", "Files"), (0 until level().itemCount).map { level().getItemAt(it) })
            level().selectedIndex = 0; layoutTree(canvas)
            assertEquals(listOf("topic:0:storage", "topic:0:ui"), names().filter { it.startsWith("topic:") && it != "topic:pending" }.sorted())
            assertEquals(0, level().selectedIndex)
            mode("Document").doClick(); layoutTree(canvas)
            assertEquals(0, level().selectedIndex)
            mode("Hierarchy").doClick(); layoutTree(canvas)
            assertEquals(0, level().selectedIndex)
            level().selectedItem = "Files"; layoutTree(canvas)
            assertTrue(names().containsAll(files.map { "file:${it.path}" }))

            // Selecting a topic outlines it and its files, and survives summary updates.
            click(named("topic:2:storage > user > auth"))
            assertTrue(canvas.topicsView.isSelected(panel().outline.nodes.single { it.identity == "storage > user > auth" }))
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(tree.nodes.single { it.path == "ui/form.kt" }.id to "Validates the card number."))
            canvas.updateTopics(TopicsCard(topics, more, canRun = false))
            assertEquals(bounds, canvas.components.single().bounds)
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == "Validates the card number." })
            assertEquals("storage > user > auth", canvas.topicsView.selected?.substringAfter(':'))
            descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "auth.kt" }.doClick()
            assertEquals(listOf("src/auth.kt"), opened)
            // Back in the document, the diffs return and the selection carries over.
            mode("Document").doClick(); layoutTree(canvas)
            val document = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            assertTrue(descendants(document).any { it is PatchView })
            assertTrue(canvas.topicsView.isSelected(document.outline.nodes.single { it.identity == "storage > user > auth" }))
            canvas.updateTopics(null)
            assertTrue(descendants(canvas).none { it is ChangeTopicsPanel || it is ChangeTopicHierarchyPanel })
        } finally { canvas.dispose() }
    }

    fun testSummariesHierarchySpansFolderRowsWithSummariesBesideFilesAndFolds() {
        val files = listOf("src/wallet/Wallet.kt", "src/wallet/Ledger.kt", "src/auth/Auth.kt", "docs/README.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        fun id(identity: String) = tree.nodes.single { it.identity == identity }.id
        val written = listOf("file:src/wallet/Ledger.kt", "file:src/wallet/Wallet.kt", "file:src/auth/Auth.kt", "file:docs/README.md", "directory:src/wallet")
        val summaries = ReviewSummaries(tree).summarize(written.associate { id(it) to "Summary of $it." })
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900); summariesView.mode = ReviewSummariesMode.OUTLINE }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun names() = panel().components.mapNotNull { it.name }
        fun named(name: String) = panel().components.single { it.name == name }
        fun note(identity: String) = (named("summary:$identity") as javax.swing.text.JTextComponent).text
        fun bottom(component: Component) = component.y + component.height
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        fun toggleButton(text: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:${text.lowercase()}" }
        fun level() = descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            // The hierarchy is a second mode of the same card.
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeSummariesPanel>().size)
            toggleButton("Hierarchy").doClick(); layoutTree(canvas)
            assertTrue(descendants(canvas).none { it is ChangeSummariesPanel })
            assertEquals(ReviewSummariesMode.HIERARCHY, canvas.summariesView.mode)

            // The root folder in the first column, then its folders; each file is a leaf one column right of its folder.
            val root = named("node:root")
            val src = named("node:directory:src")
            val wallet = named("node:directory:src/wallet")
            val ledger = named("file:src/wallet/Ledger.kt")
            val walletFile = named("file:src/wallet/Wallet.kt")
            val auth = named("file:src/auth/Auth.kt")
            val readme = named("file:docs/README.md")
            val docs = named("node:directory:docs")
            assertTrue(root.x < src.x && src.x < wallet.x && wallet.x < ledger.x)
            assertEquals(src.x, docs.x)
            assertEquals(wallet.x, named("node:directory:src/auth").x)
            assertEquals(ledger.x, auth.x)
            assertEquals(wallet.x, readme.x)
            // Every folder spans exactly the rows beneath it; folders come first, then alphabetical.
            assertEquals(readme.y, root.y)
            assertEquals(bottom(walletFile), bottom(root))
            assertEquals(readme.y, docs.y)
            assertEquals(bottom(readme), bottom(docs))
            assertEquals(auth.y, src.y)
            assertEquals(bottom(walletFile), bottom(src))
            assertEquals(ledger.y, wallet.y)
            assertEquals(bottom(walletFile), bottom(wallet))
            // Summaries start right of their file, level with it.
            for (file in files) {
                val leaf = named("file:${file.path}")
                val beside = named("summary:file:${file.path}")
                assertEquals(leaf.y, beside.y)
                assertTrue(beside.x >= leaf.x + leaf.width)
                assertEquals("Summary of file:${file.path}.", note("file:${file.path}"))
            }

            // A folded folder takes one row and shows its own summary, else what it hides; the outline folds with it.
            click(descendants(panel()).single { it.name == "toggle:directory:src/wallet" })
            assertFalse("file:src/wallet/Ledger.kt" in names())
            assertEquals("Summary of directory:src/wallet.", note("directory:src/wallet"))
            assertFalse("directory:src/wallet" in canvas.summariesView.expanded)
            click(descendants(panel()).single { it.name == "toggle:directory:src" })
            assertEquals("No summary yet · auth/, wallet/", note("directory:src"))
            assertEquals(named("node:directory:src").y, named("summary:directory:src").y)

            // Picking a level immediately opens through it and folds deeper folders.
            assertEquals(listOf(1, 2, 3), (0 until level().itemCount).map { level().getItemAt(it) })
            level().selectedItem = 1
            layoutTree(canvas)
            assertTrue("node:directory:src" in names())
            assertFalse("file:src/wallet/Ledger.kt" in names())
            assertEquals(1, level().selectedItem)
            level().selectedItem = 3
            layoutTree(canvas)
            assertTrue(names().containsAll(files.map { "file:${it.path}" }))
            assertEquals(3, level().selectedItem)

            // Selecting a folder outlines it and survives summary updates.
            click(named("node:directory:src"))
            assertEquals("directory:src", canvas.summariesView.selected)
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(id("directory:src") to "Adds wallets and auth."))
            canvas.updateSummaries(SummariesCard(more, running = false, canRun = true))
            assertEquals(bounds, canvas.components.single().bounds)
            assertEquals("directory:src", canvas.summariesView.selected)
            descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "Auth.kt" }.doClick()
            assertEquals(listOf("src/auth/Auth.kt"), opened)
            // Back in the outline, metadata is already in the header without opening extra rows.
            toggleButton("Outline").doClick(); layoutTree(canvas)
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeSummariesPanel>().size)
            assertTrue(descendants(canvas).none { it.name?.startsWith("details:") == true })
            val folderHeader = descendants(canvas).single { it.name == "summary-name:directory:src" } as JLabel
            assertEquals("src/ · folder · 3 files", folderHeader.text)
            assertTrue(folderHeader.toolTipText.contains("/repo/src/"))
        } finally { canvas.dispose() }
    }

    fun testSummaryLevelSelectionKeepsDeeperFoldersFoldedAcrossModes() {
        val paths = listOf("root.kt") + (1..6).map { depth ->
            (1..depth).joinToString("/") { "dir$it" } + "/file$depth.kt"
        }
        val files = paths.map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val canvas = canvas().apply { setSize(1800, 900) }
        fun selector() = descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), running = false, canRun = false)))
            for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.filter { it != ReviewSummariesMode.EXPLORER }) {
                (descendants(canvas).single { it.name == "summaries-mode:${mode.name.lowercase()}" } as javax.swing.JToggleButton).doClick()
                assertTrue(descendants(canvas).none { it.name in setOf("summary-fold", "summary-unfold") })
                selector().selectedItem = 4
                layoutTree(canvas)
                assertEquals(4, selector().selectedItem)
                for (node in tree.nodes.filter(::isFolderBranch)) {
                    assertEquals(node.identity, tree.depth(node.id) < 4, node.identity in canvas.summariesView.expanded)
                }
                if (mode == ReviewSummariesMode.SPLIT) assertTrue(descendants(canvas).filterIsInstance<JComponent>()
                    .any { it.name?.startsWith("summary-tree:") == true && it.toolTipText.orEmpty().contains("Level 4") })
                else assertTrue(descendants(canvas).filterIsInstance<JLabel>().any { it.text == "Level 4" })
                if (mode == ReviewSummariesMode.OUTLINE) {
                    assertEquals("Level 1", (descendants(canvas).single { it.name == "summary-level-label:root" } as JLabel).text)
                }
                selector().selectedItem = 3
                for (node in tree.nodes.filter(::isFolderBranch)) {
                    assertEquals(node.identity, tree.depth(node.id) < 3, node.identity in canvas.summariesView.expanded)
                }
                assertEquals(3, selector().selectedItem)
            }
        } finally { canvas.dispose() }
    }

    fun testSummariesDocumentAlignsFolderRailAndSummariesWithFileDiffs() {
        val files = listOf("src/wallet/Wallet.kt", "src/wallet/Ledger.kt", "src/auth/Auth.kt", "docs/README.md").mapIndexed { index, path ->
            ReviewFile(path, "/repo/$path", null, "modified",
                "@@ -1,2 +1,${2 + index * 6} @@\n ctx\n-old\n" + "+new\n".repeat(1 + index * 6), "1")
        }
        val tree = buildSummaryTree(files)
        fun id(identity: String) = tree.nodes.single { it.identity == identity }.id
        val summaries = ReviewSummaries(tree).summarize(mapOf(id("file:src/auth/Auth.kt") to "Adds token refresh."))
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        fun toggleButton(text: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:${text.lowercase()}" }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            toggleButton("Document").doClick(); layoutTree(canvas)
            assertEquals(ReviewSummariesMode.DOCUMENT, canvas.summariesView.mode)
            assertTrue(descendants(canvas).none { it is ChangeSummariesPanel || it is ChangeSummaryHierarchyPanel })
            val panel = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
            fun named(name: String) = panel.components.single { it.name == name }
            // Tree order: subfolders before files, then alphabetical.
            assertEquals(listOf("docs/README.md", "src/auth/Auth.kt", "src/wallet/Ledger.kt", "src/wallet/Wallet.kt"), panel.files.map { it.path })
            val sections = panel.files.map { named("file:${it.path}") }
            val root = named("node:root")
            val src = named("node:directory:src")
            val wallet = named("node:directory:src/wallet")
            // Each folder card spans the diffs of its files, with the root outermost and deepest folder next to the diffs.
            assertEquals(sections[0].y, root.y)
            assertEquals(sections[3].y + sections[3].height, root.y + root.height)
            assertEquals(sections[1].y, src.y)
            assertEquals(sections[3].y + sections[3].height, src.y + src.height)
            assertEquals(sections[2].y, wallet.y)
            assertEquals(sections[3].y + sections[3].height, wallet.y + wallet.height)
            assertTrue("Rail sits right of the document", wallet.x >= sections[0].x + sections[0].width)
            assertTrue(root.x > src.x && src.x > wallet.x)
            // Summaries start level with their file's diff, left of it, and follow sticky scrolling.
            panel.files.forEachIndexed { index, file ->
                val note = named("summary:${file.path}")
                assertEquals(sections[index].y, note.y)
                assertTrue(note.x + note.width <= sections[index].x)
                assertTrue(sections[index].height >= note.height)
            }
            val column = named("summary:src/wallet/Wallet.kt") as Container
            val text = column.getComponent(0)
            panel.updateStickyHeaders(column.y + 5)
            assertEquals(5, text.y)
            panel.updateStickyHeaders(0)
            assertEquals(0, text.y)
            val texts = descendants(panel).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
            assertTrue(texts.contains("Adds token refresh."))
            assertEquals(3, texts.count { it == "No summary yet" })

            // Selecting a folder on the rail outlines it, survives updates, and carries over to Hierarchy and Outline.
            src.dispatchEvent(MouseEvent(src, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            assertEquals("directory:src", canvas.summariesView.selected)
            // The folder's own summary replaces the notes of its files; the rest keep theirs.
            val srcSummary = named("card-summary:directory:src") as Container
            assertEquals(src.y, srcSummary.y)
            assertTrue(srcSummary.height >= src.height)
            assertTrue(descendants(srcSummary).filterIsInstance<JLabel>().any { it.text == "Folder summary · 3 files" })
            assertTrue(panel.files.drop(1).none { named("summary:${it.path}").isVisible })
            assertTrue(named("summary:docs/README.md").isVisible)
            val more = summaries.summarize(mapOf(id("file:docs/README.md") to "Documents the setup."))
            canvas.updateSummaries(SummariesCard(more, running = false, canRun = false))
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == "Documents the setup." })
            assertEquals("directory:src", canvas.summariesView.selected)
            descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "Auth.kt" }.doClick()
            assertEquals(listOf("src/auth/Auth.kt"), opened)
            toggleButton("Hierarchy").doClick(); layoutTree(canvas)
            assertEquals("directory:src", canvas.summariesView.selected)
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().size)
        } finally { canvas.dispose() }
    }

    fun testLongPathsKeepDiffButtonsVisibleAndClickable() {
        val paths = listOf(
            "src/main/kotlin/dev/insight/deeply/nested/module/implementation/LongFeatureService.kt",
            "VeryLongFeatureServiceName".repeat(12) + ".kt",
        )
        val requested = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, project = project, sourceActions = SourceCanvasActions(showDiffCard = { requested += it }))
            .apply { setSize(1400, 900) }
        try {
            val sources = paths.map { ReviewSourceBlock(ReviewSourceRange(it, 1, 1), "val a = 1") }
            canvas.showNotes(CanvasState(paths.map { ReviewNote(listOf(it), "Explanation.") }, sources))
            run {
                layoutTree(canvas)
                val buttons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                    .filter { it.toolTipText == "Show diff in tab" }
                assertEquals(4, buttons.size)
                requested.clear()
                buttons.forEach { button ->
                    assertTrue("Diff button must retain its width", button.width >= button.preferredSize.width)
                    assertTrue("Diff button must fit inside its file row", button.parent.contains(
                        javax.swing.SwingUtilities.convertPoint(button, Point(button.width - 1, button.height - 1), button.parent)))
                    button.doClick()
                }
                assertEquals(paths + paths, requested)
                opened.clear()
                descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().forEach { it.doClick() }
                assertEquals(paths + paths, opened)
            }
        } finally { canvas.dispose() }
    }

    fun testDiffViewerModeChangesKeepFontSize() {
        val file = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "-old\n+new\n", "1", "val a = 1\n", "val a = 2\n")
        val settings = com.intellij.diff.impl.DiffSettingsHolder.DiffSettings.getSettings()
        val originalToolOrder = settings.diffToolsOrder.toList()
        val canvas = NoteCanvas({}, {}, project = project).apply { setSize(1400, 900) }
        try {
            canvas.showNotes(CanvasState(emptyList(), diffs = listOf(file)))
            val viewer = descendants(canvas).filterIsInstance<DiffFileEditor>().single()
            val switchTool = com.intellij.diff.impl.DiffRequestProcessor::class.java
                .getDeclaredMethod("switchToDiffTool", com.intellij.diff.DiffTool::class.java).apply { isAccessible = true }
            switchTool.invoke(viewer.panel, com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            run {
                val expectedFontSize = viewer.editors.first().colorsScheme.editorFontSize2D
                val cardBounds = canvas.components.single().bounds
                for (tool in listOf(com.intellij.diff.tools.fragmented.UnifiedDiffTool.INSTANCE, com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE)) {
                    val previousEditors = viewer.editors
                    switchTool.invoke(viewer.panel, tool)
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    val editors = viewer.editors
                    assertEquals(if (tool is com.intellij.diff.tools.fragmented.UnifiedDiffTool) 1 else 2, editors.size)
                    assertTrue(previousEditors.all { it.isDisposed })
                    editors.forEach { assertEquals(expectedFontSize, it.colorsScheme.editorFontSize2D, 0.01f) }
                    assertEquals(cardBounds, canvas.components.single().bounds)
                }
            }
        } finally {
            canvas.dispose()
            settings.diffToolsOrder = originalToolOrder
        }
    }

    fun testSourceCardsUseNativeViewersAndReleaseOnlyRemovedEditors() {
        val source = ReviewSourceBlock(ReviewSourceRange("src/a.kt", 8, 9), "val x = 1\n  println(x)")
        val canvas = NoteCanvas({}, {}, project = project).apply { setSize(1000, 700) }
        try {
            canvas.showNotes(CanvasState(listOf(note), listOf(source)))
            assertEquals(2, canvas.tabs.tabCount)
            val viewer = descendants(canvas).filterIsInstance<DiffSourceEditor>().single().editor
            assertTrue(viewer.isViewer)
            assertEquals(source.text, viewer.document.text)
            assertEquals(8, viewer.gutterComponentEx.primaryLineNumberConverter.convert(viewer, 1))
            assertEquals(9, viewer.gutterComponentEx.primaryLineNumberConverter.convert(viewer, 2))
            assertTrue(viewer.settings.isLineNumbersShown)
            viewer.selectionModel.setSelection(0, 3)
            assertSame(viewer, descendants(canvas).filterIsInstance<DiffSourceEditor>().single().editor)
            assertEquals("val", viewer.selectionModel.selectedText)
            canvas.showNotes(CanvasState(listOf(note), listOf(source, source.copy(range = source.range.copy(side = "before")))))
            val other = descendants(canvas).filterIsInstance<DiffSourceEditor>().single { it.editor !== viewer }.editor
            canvas.showNotes(CanvasState(listOf(note), listOf(source.copy(range = source.range.copy(side = "before")))))
            assertTrue(viewer.isDisposed)
            assertFalse(other.isDisposed)
            canvas.dispose()
            assertTrue(other.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testDiffCardsOpenFromBlockButtonsUseNativeDiffViewersAndReleaseWhenRemoved() {
        val fileA = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "-old\n+new\n", "1", "val a = 1\n", "val a = 2\n")
        val fileB = ReviewFile("src/b.kt", "/project/src/b.kt", null, "added", "+added\n", "1", "", "val b = 3\n")
        val requested = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val canvas = NoteCanvas({}, {}, project = project, sourceActions = SourceCanvasActions(showDiffCard = { requested += it }, removeDiff = { removed += it }))
            .apply { setSize(1400, 900) }
        try {
            val source = ReviewSourceBlock(ReviewSourceRange("src/b.kt", 1, 1), "val b = 3")
            canvas.showNotes(CanvasState(listOf(note), listOf(source)))
            val diffButtons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                .filter { it.toolTipText == "Show diff in tab" }
            assertEquals(2, diffButtons.size)
            diffButtons.forEach { it.doClick() }
            assertEquals(listOf("src/a.kt", "src/b.kt"), requested)

            canvas.showNotes(CanvasState(listOf(note), listOf(source), listOf(fileA, fileB)))
            assertEquals(4, canvas.tabs.tabCount)
            val diffViewers = descendants(canvas).filterIsInstance<DiffFileEditor>()
            assertEquals(2, diffViewers.size)
            val viewerA = diffViewers.single { it.file == fileA }
            val viewerB = diffViewers.single { it.file == fileB }
            assertTrue(viewerA.editors.isNotEmpty() && viewerA.editors.all { it.isViewer })
            assertEquals(setOf("val a = 1\n", "val a = 2\n"), viewerA.editors.map { it.document.text }.toSet())

            assertSame(viewerA, descendants(canvas).filterIsInstance<DiffFileEditor>().single { it.file == fileA })

            val removeButtons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                .filter { it.toolTipText == "Remove diff block" }
            assertEquals(2, removeButtons.size)
            removeButtons.first().doClick()
            assertEquals(listOf("src/a.kt"), removed)

            canvas.showNotes(CanvasState(listOf(note), listOf(source), listOf(fileB)))
            assertTrue(viewerA.isDisposed && viewerA.editors.all { it.isDisposed })
            assertFalse(viewerB.isDisposed)
            canvas.dispose()
            assertTrue(viewerB.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testSymbolNotesHaveSeparateTabsAndDiagramBounds() {
        val symbol = ReviewSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        val evidence = ReviewSymbolEvidence(symbol, listOf(ReviewSymbolLinks("Calls", (1..3).map {
            symbol.copy(name = "callee$it", qualifiedName = "Store.callee$it", offset = 10 + it)
        }, 20)))
        val semantic = note.copy(symbol = symbol.selector, evidence = evidence)
        val canvas = canvas()
        canvas.showNotes(CanvasState(listOf(note, semantic)))
        assertEquals(2, canvas.tabs.tabCount)
        canvas.focusNote(semantic)
        val title = canvas.tabs.getTitleAt(canvas.tabs.selectedIndex)
        val diagram = descendants(canvas).filterIsInstance<DiffSymbolDiagram>().single()
        assertEquals(5, diagram.componentCount) // Root, three callees, one omitted count.
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "+17 calls not shown" })
        canvas.showNotes(CanvasState(listOf(note, semantic.copy(text = "Updated"))))
        assertEquals(title, canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
        canvas.showNotes(CanvasState(listOf(semantic)))
        assertEquals(title, canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
    }

    fun testDiagramBoundsMixedRelationshipsAndDistinguishesOwners() {
        val symbol = ReviewSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        fun neighbors(owner: String) = (1..3).map {
            symbol.copy(qualifiedName = "$owner$it.save", offset = 10 + it)
        }
        val diagram = DiffSymbolDiagram(ReviewSymbolEvidence(symbol, listOf(
            ReviewSymbolLinks("Callers in this file", neighbors("Caller"), 5, incoming = true),
            ReviewSymbolLinks("Calls", neighbors("Target"), 4),
            ReviewSymbolLinks("References", neighbors("Other"), 3),
        )), 360, 1.0, {})
        val links = descendants(diagram).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals(7, links.size) // Root plus six neighbors across all relationship groups.
        assertTrue(links.any { it.text == "Caller1.save" })
        assertTrue(links.any { it.text == "Target1.save" })
        assertFalse(links.any { it.text.startsWith("Other") })
        val labels = descendants(diagram).filterIsInstance<JLabel>().map { it.text }
        assertTrue("+2 callers in this file not shown" in labels)
        assertTrue("+1 calls not shown" in labels)
        assertTrue("+3 references not shown" in labels)
        diagram.setSize(diagram.preferredSize)
        assertTrue(diagram.components.all { diagram.bounds.contains(it.bounds) })
    }

    fun testChangeFlowCardDrawsCallTreeWithRepeatsAndFocusesNotes() {
        val base = ReviewSymbol("src/a.kt", "/src/a.kt", "a", "A.a", dev.insight.callgraph.SymbolKind.FUNCTION, 1, 0)
        fun step(name: String, vararg calls: String) = ReviewNote(listOf("src/a.kt"), "$name does work. More detail.", "s:$name",
            ReviewSymbolEvidence(base.copy(name = name, qualifiedName = "A.$name", offset = name.hashCode()), emptyList(),
                calls.map { "src/a.kt#A.$it:1@${it.hashCode()}" }.toSet()))
        // a → b → c → a is a cycle, a → c shares c, and d is unrelated.
        val notes = listOf(step("a", "b", "c", "external"), step("b", "c", "b"), step("c", "a"), step("d"))
        val flow = changeFlow(notes)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2, "a" to 3, "c" to 1),
            flow.rows.map { it.note.evidence!!.symbol.name to it.depth })
        assertEquals(listOf(false, false, false, true, true), flow.rows.map { it.repeat })
        assertEquals(1, flow.unlinked)
        assertTrue(changeFlow(listOf(step("a"), note)).rows.isEmpty())

        val canvas = canvas()
        canvas.showNotes(CanvasState(notes))
        assertEquals(5, canvas.tabs.tabCount) // Flow card plus four notes.
        val diagram = descendants(canvas).filterIsInstance<ChangeFlowDiagram>().single()
        val links = descendants(diagram).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals(listOf("A.a", "A.b", "A.c", "↺ A.a", "↺ A.c"), links.map { it.text })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "b does work" })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "1 other symbol notes have no calls to or from these" })
        diagram.setSize(diagram.preferredSize)
        assertTrue(diagram.components.all { diagram.bounds.contains(it.bounds) })

        links[2].doClick()
        assertTrue(descendants(canvas.tabs.selectedComponent as Container).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text.startsWith("c ") })
        canvas.showNotes(CanvasState(notes.take(3)))
        assertEquals(4, canvas.tabs.tabCount)
        canvas.showNotes(CanvasState(listOf(notes[3])))
        assertEquals(1, canvas.tabs.tabCount) // The flow disappears once no calls connect notes.
    }

    fun testCancelSearchClearsTextFilterWithoutTraceLauncher() {
        val file = ReviewFile("Canvas.kt", "/repo/Canvas.kt", null, "modified", "-old\n+new\n", "1")
        val summaries = ReviewSummaries(buildSummaryTree(listOf(file)))
        val canvas = NoteCanvas({}, {})
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            val field = descendants(canvas).single { it.name == "summaries-search" } as com.intellij.ui.SearchTextField
            field.text = "no-such-summary"
            assertTrue(descendants(canvas).any { it.name == "no-matches" })
            (descendants(canvas).single { it.name == "summaries-cancel-search" } as javax.swing.JButton).doClick()
            assertEquals("", field.text)
            assertNull(canvas.summariesView.shownNodes(summaries))
            assertTrue(descendants(canvas).none { it.name == "no-matches" })
        } finally { canvas.dispose() }
    }

    fun testSearchWithNoMatchesHidesAllSummariesAndKeepsItsBreadcrumb() {
        val tree = buildSummaryTree(listOf("a.kt", "b.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        })
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Deletes ${it.label}." })
        val state = ChangeSummariesViewState(filtering = true)
        val context = selectionContext(tree.root.identity, summaries.text(tree.root.id)!!, 0, 7)!!
        val trace = SummaryTrace(context.identity, context.summary, context.word, context.context, context.query,
            tree.root.children.map { SummaryMatch(it, tree[it].identity, false, 1) })
        state.shownNodes(summaries)
        state.showTrace(tree, trace)
        assertEquals(emptySet<String>(), state.shownNodes(summaries))
        assertEquals(context.query, state.crumbs.single().query)
        assertNull(state.resummaryRequest(summaries))
        state.backTo(0)
        assertNull(state.shownNodes(summaries))
    }

    fun testFuzzyMatchingIsNaiveButForgiving() {
        assertTrue(fuzzyMatches("summary", "ChangeSummaryModel.kt"))
        assertTrue("Letters in order of one word", fuzzyMatches("chsum", "ChangeSummaryModel.kt builds the tree"))
        assertTrue("One typo from four letters on", fuzzyMatches("trea", "builds the tree"))
        assertTrue("Every word must match", fuzzyMatches("tree builds", "builds the tree"))
        assertFalse(fuzzyMatches("tree parser", "builds the tree"))
        assertFalse("Short words need an exact substring", fuzzyMatches("tx", "builds the tree"))
    }

    /**
     * Files cite their removed line; ui/ cites each file; docs/ repeats its only file; the root's first part cites both ui/
     * parts and its last part the guide.
     */
    private fun linkedSummaries(): ReviewSummaries {
        val files = listOf("ui/Canvas.kt", "ui/Hierarchy.kt", "docs/Guide.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1", before = "old\n", after = "")
        }
        val tree = buildSummaryTree(files)
        fun node(path: String) = tree.nodes.single { it.path == path }
        fun part(text: String, vararg sources: SummarySource) = SummarySegment(text, sources.toList())
        return ReviewSummaries(tree)
            .summarize(mapOf(
                node("ui/Canvas.kt").id to Summary(listOf(part("Deletes the canvas view.", SummarySource.Rows(2, 2)))),
                node("ui/Hierarchy.kt").id to Summary(listOf(part("Deletes the hierarchy view.", SummarySource.Rows(2, 2)))),
                node("docs/Guide.md").id to Summary(listOf(part("Documents the views.", SummarySource.Rows(2, 2)))),
            ))
            .summarize(mapOf(node("ui").id to Summary(listOf(
                part("Deletes the canvas view", SummarySource.Claim(0, 0)), part(" and "),
                part("the hierarchy view.", SummarySource.Claim(1, 0)),
            ))))
            .summarize(mapOf(tree.root.id to Summary(listOf(
                part("Removes the canvas and hierarchy views", SummarySource.Claim(1, 0), SummarySource.Claim(1, 2)), part(", "),
                part("updating the guide.", SummarySource.Claim(0, 0)),
            ))))
    }

    private fun summaryText(canvas: Container, identity: String) =
        descendants(descendants(canvas).single { it.name == "summary:$identity" } as Container)
            .filterIsInstance<javax.swing.text.JTextComponent>().first()

    private fun pointAtWord(canvas: Container, area: javax.swing.text.JTextComponent, word: String, event: Int) {
        val box = area.modelToView2D(area.text.indexOf(word) + 1)
        area.dispatchEvent(MouseEvent(area, event, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 1, false,
            if (event == MouseEvent.MOUSE_CLICKED) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON))
        layoutTree(canvas)
    }

    /** Chooses [item] from the context menu of [word] in [area], as a right click on it does. */
    private fun chooseOnWord(canvas: Container, area: javax.swing.text.JTextComponent, word: String, item: String) {
        val box = area.modelToView2D(area.text.indexOf(word) + 1)
        selectionMenu(area, Point(box.centerX.toInt(), box.centerY.toInt()))!!
            .components.filterIsInstance<javax.swing.JMenuItem>().single { it.name == item }.doClick()
        layoutTree(canvas)
    }

    private fun highlighted(area: javax.swing.text.JTextComponent): List<String> =
        area.highlighter.highlights.sortedBy { it.startOffset }.map { area.text.substring(it.startOffset, it.endOffset) }

    fun testHoverHighlightsTheCitedPartAndEchoesWhatItCondensesAndWhatCondensesIt() {
        val summaries = linkedSummaries()
        val tree = summaries.tree
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.OUTLINE
            summariesView.expanded += tree.nodes.map { it.identity }
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            layoutTree(canvas)
            fun rowBounds() = descendants(canvas).filter { it.name?.startsWith("summary:") == true }.associate { row ->
                row.name to javax.swing.SwingUtilities.convertRectangle(row.parent, row.bounds, canvas)
            }
            val before = rowBounds()
            val root = summaryText(canvas, "root")
            pointAtWord(canvas, root, "canvas", MouseEvent.MOUSE_MOVED)
            assertEquals("Hover must not move any summary row", before, rowBounds())
            assertEquals("Removes the canvas and hierarchy views", canvas.summariesView.tracing!!.context.query)
            assertEquals(listOf("Removes", "the", "canvas", "and", "hierarchy", "views"), highlighted(root))
            // Both parts of ui/ and both of its files are echoed; the guide is not.
            assertEquals(listOf("Deletes", "the", "canvas", "view", "the", "hierarchy", "view"), highlighted(summaryText(canvas, "directory:ui")))
            assertEquals(listOf("Deletes", "the", "canvas", "view"), highlighted(summaryText(canvas, "file:ui/Canvas.kt")))
            assertEquals(emptyList<String>(), highlighted(summaryText(canvas, "file:docs/Guide.md")))
            // Hovering a file echoes what condenses it above, up to the root.
            pointAtWord(canvas, summaryText(canvas, "file:ui/Hierarchy.kt"), "hierarchy", MouseEvent.MOUSE_MOVED)
            assertEquals(listOf("the", "hierarchy", "view"), highlighted(summaryText(canvas, "directory:ui")))
            assertEquals(listOf("Removes", "the", "canvas", "and", "hierarchy", "views"), highlighted(summaryText(canvas, "root")))
            assertEquals(emptyList<String>(), highlighted(summaryText(canvas, "file:ui/Canvas.kt")))
        } finally { canvas.dispose() }
    }

    fun testSearchShowsTheCitedSummariesAndAFileOpensItsCitedLines() {
        val summaries = linkedSummaries()
        val tree = summaries.tree
        val opened = mutableListOf<Pair<String, LineTrace>>()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(openLines = { node, lines -> opened += node.path to lines })).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.OUTLINE
            summariesView.expanded += tree.nodes.map { it.identity }
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            layoutTree(canvas)
            // A left click on a linked word places the caret; only the menu searches.
            pointAtWord(canvas, summaryText(canvas, "root"), "guide", MouseEvent.MOUSE_CLICKED)
            assertNull(canvas.summariesView.tracing?.trace)
            chooseOnWord(canvas, summaryText(canvas, "root"), "guide", "search-segment")
            val trace = canvas.summariesView.tracing!!.trace!!
            // docs/ only repeats its file, so the guide itself is the match one level below.
            assertEquals(listOf("file:docs/Guide.md"), trace.related.map { it.identity })
            assertEquals(4, trace.candidates)
            assertEquals("file:docs/Guide.md", canvas.summariesView.selected)
            val status = descendants(canvas).single { it.name == "trace-status" } as JLabel
            assertTrue(status.text, status.text.contains("cites 1 of 4 summaries"))

            chooseOnWord(canvas, summaryText(canvas, "file:ui/Canvas.kt"), "canvas", "search-segment")
            val (path, lines) = opened.single()
            assertEquals("ui/Canvas.kt", path)
            assertEquals(listOf(1), lines.before)
            assertTrue(lines.after.isEmpty())
            assertTrue(status.text, status.text.contains("Canvas.kt:removed 1"))
        } finally { canvas.dispose() }
    }

    fun testSearchTabKeepsOnlyTheCitedSummaries() {
        val summaries = linkedSummaries()
        val tree = summaries.tree
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.OUTLINE
            summariesView.expanded += tree.nodes.map { it.identity }
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            layoutTree(canvas)
            chooseOnWord(canvas, summaryText(canvas, "root"), "hierarchy", "search-tab")
            assertEquals(2, canvas.tabs.tabCount)
            val tab = canvas.tabs.getComponentAt(1) as Container
            val rows = descendants(tab).mapNotNull { it.name }.filter { it.startsWith("summary:") }
            assertEquals(listOf("summary:root", "summary:directory:ui", "summary:file:ui/Canvas.kt", "summary:file:ui/Hierarchy.kt"), rows)
            val crumbs = descendants(tab).filter { it.name?.startsWith("summaries-crumb:") == true }
            assertEquals(listOf("Summaries", "“Removes the canvas and hierarchy views”"), crumbs.map { (it as? JLabel)?.text ?: (it as com.intellij.ui.components.ActionLink).text })
        } finally { canvas.dispose() }
    }

    private fun canvas() =NoteCanvas({}, {}).apply { setSize(1000, 700); showNotes(CanvasState(listOf(note))) }
    private fun clickSummary(canvas: NoteCanvas) {
        val row = descendants(canvas).single { it.name == "summary:root" }
        row.dispatchEvent(MouseEvent(row, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
        layoutTree(canvas)
    }
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    /** Check the laid out Swing text, including the wrapping performed when it is painted. */
    private fun assertWordFitsOnOneLine(text: javax.swing.text.JTextComponent, word: String) {
        val image = java.awt.image.BufferedImage(text.width, text.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { text.paint(graphics) } finally { graphics.dispose() }
        val start = text.text.indexOf(word)
        assertTrue(start >= 0)
        val first = text.modelToView2D(start)
        val last = text.modelToView2D(start + word.length - 1)
        assertEquals("Word was split across lines in ${text.text}", first.y, last.y, 0.01)
        assertTrue("Text is clipped vertically", last.maxY <= text.height)
    }

}
