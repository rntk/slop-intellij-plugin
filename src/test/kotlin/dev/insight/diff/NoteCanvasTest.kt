package dev.insight.diff

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
import dev.insight.review.resummaryRequest
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
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = FakeTraceLauncher())).apply {
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
        val files = listOf("src/feature/one.kt", "src/feature/two.kt", "docs/readme.md").map { path ->
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
            fun summaryLevel(value: Int, action: String) {
                (descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>).selectedItem = value
                (descendants(canvas).single { it.name == "summary-$action" } as javax.swing.JButton).doClick()
            }
            layoutTree(canvas)
            val topicWidth = topicPanel().preferredSize.width
            val folderWidth = folderPanel().preferredSize.width
            topicLevel().selectedIndex = 0
            summaryLevel(1, "fold")
            layoutTree(canvas)
            assertEquals(0, topicLevel().selectedIndex)
            assertEquals(0, canvas.summariesView.shownLevel(summaries.tree))
            assertEquals(setOf("topic:0:product", "topic:pending"), topicPanel().components.mapNotNull { it.name }.filter { it.startsWith("topic:") }.toSet())
            assertEquals(listOf("node:root"), folderPanel().components.mapNotNull { it.name }.filter { it.startsWith("node:") })
            assertTrue(topicPanel().preferredSize.width < topicWidth)
            assertTrue(folderPanel().preferredSize.width < folderWidth)
            for (panel in listOf(topicPanel(), folderPanel())) {
                assertEquals(files.size, descendants(panel).filterIsInstance<PatchView>().size)
            }
            topicLevel().selectedItem = "Files"
            summaryLevel(ChangeSummariesViewState.branchLevels(summaries.tree), "unfold")
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
            outer.verticalScrollBar.value = 120
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
            outer.verticalScrollBar.value = 120
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
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
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
        fun fold(action: String) = (descendants(canvas).single { it.name == "summary-$action" } as javax.swing.JButton).doClick()
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            // The outline stays the default; the hierarchy is a second mode of the same card.
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

            // Picking a level waits for an action; folding level 1 closes the root.
            assertEquals(listOf(1, 2, 3), (0 until level().itemCount).map { level().getItemAt(it) })
            level().selectedItem = 1
            fold("fold"); layoutTree(canvas)
            assertEquals(listOf("node:root", "summary:root"), names().sorted())
            assertEquals("No summary yet · docs/, src/", note("root"))
            assertEquals(1, level().selectedItem)
            level().selectedItem = 3
            assertEquals(listOf("node:root", "summary:root"), names().sorted())
            fold("unfold"); layoutTree(canvas)
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

    fun testSummaryLevelActionsKeepDeeperFoldersFoldedAcrossModes() {
        val paths = listOf("root.kt") + (1..6).map { depth ->
            (1..depth).joinToString("/") { "dir$it" } + "/file$depth.kt"
        }
        val files = paths.map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val canvas = canvas().apply { setSize(1800, 900) }
        fun selector() = descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>
        fun action(name: String) = (descendants(canvas).single { it.name == "summary-$name" } as javax.swing.JButton).doClick()
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), running = false, canRun = false)))
            for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.filter { it != ReviewSummariesMode.EXPLORER }) {
                (descendants(canvas).single { it.name == "summaries-mode:${mode.name.lowercase()}" } as javax.swing.JToggleButton).doClick()
                val before = canvas.summariesView.expanded.toSet()
                selector().selectedItem = 4
                assertEquals(before, canvas.summariesView.expanded)
                action("unfold")
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
                action("fold")
                for (node in tree.nodes.filter(::isFolderBranch)) {
                    assertEquals(node.identity, tree.depth(node.id) < 3, node.identity in canvas.summariesView.expanded)
                }
                assertEquals(4, selector().selectedItem)
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

    fun testSummariesPrescoreEveryWordAndSearchOnlyOnClick() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/hierarchy/Hierarchy.kt", "ui/summaries/Summaries.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Deleted views: canvas, hierarchy, summaries."
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.ROOT) root else "Deletes the ${node.label.substringBefore('/')} view."
        })
        val hierarchy = tree.nodes.single { it.path == "ui/hierarchy/Hierarchy.kt" }
        val canvasFile = tree.nodes.single { it.path == "ui/canvas/Canvas.kt" }
        val traced = mutableListOf<Pair<Int, Int>>()
        val searched = mutableListOf<SummaryContext>()
        var finishSearch: ((Result<SummaryTrace>) -> Unit)? = null
        var finishScoring: ((Result<Unit>) -> Unit)? = null
        var saveContext: ((SummaryContext) -> Unit)? = null
        val searchJobs = mutableListOf<Job>()
        val scoreJobs = mutableListOf<Job>()
        val launcher = object : SummaryTraceLauncher {
            override fun prescore(summaries: ReviewSummaries, words: List<Pair<Int, Int>>,
                                  scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job {
                traced += words
                saveContext = scored
                finishScoring = done
                for ((id, word) in words) {
                    val text = summaries.text(id)!!
                    val split = SummaryWord.split(text)
                    val context = (listOf(split[0], split[1], split[word])).distinctBy { it.index }.sortedBy { it.index }
                    scored(SummaryContext(summaries.tree[id].identity, text, split[word], context, SummaryTracer.query(text, context)))
                }
                return Job().also { scoreJobs += it }
            }

            override fun search(summaries: ReviewSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job {
                searched += context
                finishSearch = done
                return Job().also { searchJobs += it }
            }

            override fun searchLines(summaries: ReviewSummaries, id: Int, context: SummaryContext, done: (Result<LineTrace>) -> Unit): Job =
                error("Only file summaries search lines")
        }
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply { setSize(1600, 900) }
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun rootText() = descendants(named("summary:root").single() as Container).filterIsInstance<javax.swing.text.JTextComponent>().first()
        fun pointAtWord(text: String, event: Int) {
            val area = rootText()
            val start = area.text.indexOf(text)
            val box = area.modelToView2D(start + 1)
            area.dispatchEvent(MouseEvent(area, event, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 1, false, if (event == MouseEvent.MOUSE_CLICKED) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON))
            layoutTree(canvas)
        }
        fun clickWord(text: String) = pointAtWord(text, MouseEvent.MOUSE_CLICKED)
        fun hoverWord(text: String) = pointAtWord(text, MouseEvent.MOUSE_MOVED)
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            assertTrue(named("summaries-interactive").isEmpty())
            assertTrue(traced.isEmpty())
            clickWord("hierarchy")
            assertNull("Unscored words must not trigger scoring on demand", canvas.summariesView.tracing)
            assertFalse("An unscored summary click folds its children", "root" in canvas.summariesView.expanded)
            val toggle = named("toggle:root").single()
            toggle.dispatchEvent(MouseEvent(toggle, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
            canvas.summariesView.selected = null
            (named("summaries-score").single() as javax.swing.JButton).doClick()
            // File summaries are scored too; their highlights search the file's lines.
            val expected = tree.nodes.flatMap { node ->
                SummaryWord.split(summaries.text(node.id)!!).filter { it.meaningful }.map { node.id to it.index }
            }
            assertEquals(expected, traced)
            assertTrue(canvas.summariesView.scoring)
            assertFalse((named("summaries-score").single() as javax.swing.JButton).isEnabled)
            finishScoring!!(Result.success(Unit))
            layoutTree(canvas)
            assertFalse(canvas.summariesView.scoring)
            val scoredCount = traced.size
            assertFalse((named("summaries-score").single() as javax.swing.JButton).isEnabled)

            fun rowBounds() = descendants(canvas).filter { it.name?.startsWith("summary:") == true }.associate { row ->
                row.name to javax.swing.SwingUtilities.convertRectangle(row.parent, row.bounds, canvas)
            }
            val beforeHover = rowBounds()
            val sizeBeforeHover = canvas.preferredSize
            hoverWord("hierarchy")
            assertEquals("Hover must not move any summary row", beforeHover, rowBounds())
            assertEquals(sizeBeforeHover, canvas.preferredSize)
            hoverWord("canvas")
            assertEquals("Changing the hover query must not move any summary row", beforeHover, rowBounds())
            hoverWord("hierarchy")
            assertEquals(scoredCount, traced.size)
            assertTrue("Scoring alone must not search candidates", searched.isEmpty())
            assertNull(canvas.summariesView.selected)
            assertEquals(3, rootText().highlighter.highlights.size)
            assertTrue((named("trace-status").single() as JLabel).text.contains("Right-click a scored word"))

            // A left click on a scored word must not search, so it cannot conflict with selecting words by hand.
            clickWord("Deleted")
            assertTrue("Clicking a scored word must not search", searched.isEmpty())

            // Choosing Search from a context word's context menu searches the original query, without rescoring that word.
            searchScoredWord(canvas, rootText(), "Deleted")
            assertEquals(scoredCount, traced.size)
            assertEquals("hierarchy", searched.single().word.text)
            assertTrue((named("trace-status").single() as JLabel).text.contains("Searching"))
            assertEquals("Starting a search must not move rows", beforeHover, rowBounds())
            searchScoredWord(canvas, rootText(), "views")
            assertEquals("Repeated searches must not start overlapping searches", 1, searched.size)
            val context = searched.single()
            val result = SummaryTrace("root", root, context.word, context.context, context.query,
                listOf(SummaryMatch(hierarchy.id, hierarchy.identity, 0.9, 1), SummaryMatch(canvasFile.id, canvasFile.identity, 0.1, 1)), 3)
            finishSearch!!(Result.success(result))
            layoutTree(canvas)
            // The best related summary is selected, labelled and its level opened; unrelated ones are not marked.
            assertEquals(hierarchy.identity, canvas.summariesView.selected)
            val badge = named("match:${hierarchy.identity}").single() as JLabel
            assertEquals("● Best match · 90%", badge.text)
            assertTrue(badge.toolTipText.startsWith("● Best match for “Deleted views: hierarchy”"))
            assertTrue(named("match:${canvasFile.identity}").isEmpty())
            assertTrue((named("trace-status").single() as JLabel).text.contains("1 of 3 summaries"))
            // The traced word and its two context words are highlighted, not the siblings.
            assertEquals(3, rootText().highlighter.highlights.size)

            searchScoredWord(canvas, rootText(), "hierarchy")
            assertEquals("Hover and searches never request scoring", scoredCount, traced.size)
            assertEquals("Completed searches are reused", 1, searched.size)

            // Moving onto another cached word cancels pending searches and ignores their late results.
            searchScoredWord(canvas, rootText(), "canvas")
            searchScoredWord(canvas, rootText(), "canvas")
            assertEquals(2, searched.size)
            hoverWord("hierarchy")
            assertTrue(searchJobs.last().isCancelled)
            finishSearch(Result.success(result))
            assertEquals("hierarchy", canvas.summariesView.tracing!!.word.text)
            assertNull(canvas.summariesView.tracing!!.trace)

            // Changed summaries lose their cached contexts; unchanged nodes keep theirs.
            val oldContext = canvas.summariesView.scored.getValue("root" to 3)
            val changed = summaries.summarize(mapOf(0 to "Updated summary."))
            canvas.updateSummaries(SummariesCard(changed, running = false, canRun = true))
            assertNull(canvas.summariesView.tracing)
            assertTrue(canvas.summariesView.scored.values.none { it.identity == "root" })
            (named("summaries-score").single() as javax.swing.JButton).doClick()
            canvas.updateSummaries(SummariesCard(summaries, running = false, canRun = true))
            assertTrue(scoreJobs.last().isCancelled)
            saveContext!!(oldContext)
            finishScoring(Result.success(Unit))
            assertTrue("Cancelled scoring must ignore late results", canvas.summariesView.scored.values.none { it.identity == "root" })
        } finally {
            canvas.dispose()
        }
    }

    /** Scores every word with the summary's first two words as context; searches wait for the test to finish them. */
    private class FakeTraceLauncher : SummaryTraceLauncher {
        val scoredWords = mutableListOf<Pair<Int, Int>>()
        val searched = mutableListOf<SummaryContext>()
        val searchJobs = mutableListOf<Job>()
        var finishSearch: ((Result<SummaryTrace>) -> Unit)? = null
        val searchedLines = mutableListOf<SummaryContext>()
        var finishLines: ((Result<LineTrace>) -> Unit)? = null

        override fun prescore(summaries: ReviewSummaries, words: List<Pair<Int, Int>>,
                              scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job {
            scoredWords += words
            for ((id, word) in words) {
                val text = summaries.text(id)!!
                val split = SummaryWord.split(text)
                val context = listOf(split[0], split[1], split[word]).distinctBy { it.index }.sortedBy { it.index }
                scored(SummaryContext(summaries.tree[id].identity, text, split[word], context, SummaryTracer.query(text, context)))
            }
            done(Result.success(Unit))
            return Job()
        }

        override fun search(summaries: ReviewSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job {
            searched += context
            finishSearch = done
            return Job().also { searchJobs += it }
        }

        override fun searchLines(summaries: ReviewSummaries, id: Int, context: SummaryContext, done: (Result<LineTrace>) -> Unit): Job {
            searchedLines += context
            finishLines = done
            return Job().also { searchJobs += it }
        }

        fun finish(context: SummaryContext, candidates: Int, vararg matches: SummaryMatch) = finishSearch!!(Result.success(
            SummaryTrace(context.identity, context.summary, context.word, context.context, context.query, matches.sortedByDescending { it.probability }, candidates)))
    }

    private fun pointAtWord(canvas: Container, area: javax.swing.text.JTextComponent, word: String, event: Int) {
        val box = area.modelToView2D(area.text.indexOf(word) + 1)
        area.dispatchEvent(MouseEvent(area, event, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 1, false,
            if (event == MouseEvent.MOUSE_CLICKED) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON))
        layoutTree(canvas)
    }

    /** Chooses Search from the context menu of the scored [word] in [area], as a right click on it does. */
    private fun searchScoredWord(canvas: Container, area: javax.swing.text.JTextComponent, word: String) {
        val box = area.modelToView2D(area.text.indexOf(word) + 1)
        val item = selectionMenu(area, Point(box.centerX.toInt(), box.centerY.toInt()))!!
            .components.filterIsInstance<javax.swing.JMenuItem>().single { it.name == "search-scored-query" }
        item.doClick()
        layoutTree(canvas)
    }

    fun testSearchTabKeepsOnlyRelatedSummariesAndNarrowsWithBreadcrumbs() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt", "ui/hierarchy/Hierarchy.kt", "ui/hierarchy/Columns.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Deleted views: canvas, hierarchy."
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.ROOT) root else "Deletes the ${node.label} view."
        })
        fun node(path: String) = tree.nodes.single { it.path == path && it.kind != SummaryKind.ROOT }
        val canvasDir = node("ui/canvas")
        val hierarchyDir = node("ui/hierarchy")
        val hierarchyFile = node("ui/hierarchy/Hierarchy.kt")
        val columnsFile = node("ui/hierarchy/Columns.kt")
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply { setSize(1600, 900) }
        fun page(index: Int) = canvas.tabs.getComponentAt(index) as Container
        fun named(index: Int, name: String) = descendants(page(index)).filter { it.name == name }
        fun shown(index: Int) = descendants(page(index)).mapNotNull { it.name?.removePrefix("summary:")?.takeIf { _ -> it.name!!.startsWith("summary:") } }.toSet()
        fun summaryArea(index: Int, identity: String) =
            descendants(named(index, "summary:$identity").single() as Container).filterIsInstance<javax.swing.text.JTextComponent>().first()
        fun menuItem(area: javax.swing.text.JTextComponent, word: String, name: String): javax.swing.JMenuItem {
            val start = area.text.indexOf(word)
            area.select(start, start + word.length)
            return selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>().single { it.name == name }
        }
        try {
            canvas.summariesView.mode = ReviewSummariesMode.OUTLINE
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            val everything = shown(0)
            assertTrue(canvasDir.identity in everything && hierarchyFile.identity in everything)
            // A file's summary has no summaries below it to keep, so it offers no Search tab.
            menuItem(summaryArea(0, hierarchyFile.identity), "Deletes", "search-selection")
            assertTrue(selectionMenu(summaryArea(0, hierarchyFile.identity))!!.components.none { it.name == "search-tab" })

            // Search tab opens a second tab and searches there; the summaries tab keeps showing everything.
            menuItem(summaryArea(0, "root"), "hierarchy", "search-tab").doClick()
            layoutTree(canvas)
            assertEquals(2, canvas.tabs.tabCount)
            assertEquals(1, canvas.tabs.selectedIndex)
            assertEquals("Search · hierarchy", canvas.tabs.getTitleAt(1))
            assertEquals("hierarchy", launcher.searched.single().query)
            assertEquals("“hierarchy”…", (named(1, "summaries-crumb:searching").single() as JLabel).text)
            launcher.finish(launcher.searched.single(), 4, SummaryMatch(hierarchyDir.id, hierarchyDir.identity, 0.9, 1),
                SummaryMatch(canvasDir.id, canvasDir.identity, 0.1, 1))
            layoutTree(canvas)
            // Only the related folder, everything beneath it and the way up to the root are left; the match is marked.
            val related = shown(1)
            assertTrue(hierarchyDir.identity in related && hierarchyFile.identity in related && columnsFile.identity in related && "root" in related)
            assertFalse(canvasDir.identity in related)
            assertEquals("● Best match · 90%", (named(1, "match:${hierarchyDir.identity}").single() as JLabel).text)
            assertEquals(TraceStyle.BEST_MATCH, named(1, "summary:${hierarchyDir.identity}").single().background)
            assertTrue(named(1, "match:${hierarchyFile.identity}").isEmpty())
            assertEquals("“hierarchy”", (named(1, "summaries-crumb:1").single() as JLabel).text)
            assertEquals(everything, shown(0))
            assertTrue(canvas.summariesView.crumbs.isEmpty())

            // Hovering another scored word keeps this breadcrumb's result highlights in place.
            (named(1, "summaries-score").single() as javax.swing.JButton).doClick()
            val resultRow = named(1, "summary:${hierarchyDir.identity}").single()
            pointAtWord(canvas, summaryArea(1, "root"), "Deleted", MouseEvent.MOUSE_MOVED)
            assertSame(resultRow, named(1, "summary:${hierarchyDir.identity}").single())
            assertEquals(TraceStyle.BEST_MATCH, resultRow.background)
            assertEquals("● Best match · 90%", (named(1, "match:${hierarchyDir.identity}").single() as JLabel).text)

            // Searching again in the search tab narrows it and adds the query to the breadcrumbs.
            menuItem(summaryArea(1, hierarchyDir.identity), "Deletes", "search-selection").doClick()
            launcher.finish(launcher.searched.last(), 2, SummaryMatch(hierarchyFile.id, hierarchyFile.identity, 0.8, 1))
            layoutTree(canvas)
            assertTrue(hierarchyFile.identity in shown(1))
            assertFalse(columnsFile.identity in shown(1))
            assertEquals("“Deletes”", (named(1, "summaries-crumb:2").single() as JLabel).text)
            assertEquals("Search · Deletes", canvas.tabs.getTitleAt(1))
            assertTrue(named(1, "match:${hierarchyDir.identity}").isEmpty())
            assertEquals("● Best match · 80%", (named(1, "match:${hierarchyFile.identity}").single() as JLabel).text)

            // An earlier breadcrumb goes back to the summaries left after it.
            (named(1, "summaries-crumb:1").single() as com.intellij.ui.components.ActionLink).doClick()
            layoutTree(canvas)
            assertTrue(columnsFile.identity in shown(1))
            assertEquals("● Best match · 90%", (named(1, "match:${hierarchyDir.identity}").single() as JLabel).text)
            assertTrue(named(1, "match:${hierarchyFile.identity}").isEmpty())
            assertEquals(1, descendants(page(1)).count { it.name?.startsWith("summaries-crumb:") == true && it.name != "summaries-crumb:0" })
            val clear = named(1, "trace-clear").single() as com.intellij.ui.components.ActionLink
            assertTrue("Restored breadcrumb highlights can be cleared", clear.isEnabled)
            clear.doClick()
            assertTrue(named(1, "match:${hierarchyDir.identity}").isEmpty())
            assertTrue(columnsFile.identity in shown(1))
            menuItem(summaryArea(1, "root"), "hierarchy", "search-selection").doClick()
            launcher.finish(launcher.searched.last(), 4, SummaryMatch(hierarchyDir.id, hierarchyDir.identity, 0.9, 1))
            layoutTree(canvas)
            assertEquals("● Best match · 90%", (named(1, "match:${hierarchyDir.identity}").single() as JLabel).text)
            assertTrue(named(1, "summaries-crumb:2").isEmpty())

            // Typing filters fuzzily, with the folders above every match, and stays out of the breadcrumbs.
            val field = named(0, "summaries-search").single() as com.intellij.ui.SearchTextField
            field.text = "colmns"
            layoutTree(canvas)
            assertEquals(generateSequence(columnsFile) { it.parent?.let(tree::get) }.map { it.identity }.toSet(), shown(0))
            assertTrue(named(0, "summaries-crumb:1").isEmpty())
            assertSame("The search field survives the rebuilt card", field, named(0, "summaries-search").single())
            field.text = "zzz"
            layoutTree(canvas)
            assertEquals(1, named(0, "no-matches").size)
            field.text = ""
            layoutTree(canvas)
            assertEquals(everything, shown(0))

            (named(1, "close-search-tab").single() as com.intellij.ui.InplaceButton).doClick()
            assertEquals(1, canvas.tabs.tabCount)
        } finally {
            canvas.dispose()
        }
    }

    fun testExplorerSearchAndSearchTabsHighlightResultsAsPagesAreOpened() {
        val tree = buildSummaryTree(listOf("ui/Canvas.kt", "ui/Unrelated.kt", "docs/Readme.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
        })
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Updates ${it.label}." })
        val folder = tree.nodes.single { it.path == "ui" }
        val matching = tree.nodes.single { it.path == "ui/Canvas.kt" }
        val rejected = tree.nodes.single { it.path == "ui/Unrelated.kt" }
        for (searchTab in listOf(false, true)) {
            val launcher = FakeTraceLauncher()
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply {
                setSize(1600, 900); summariesView.mode = ReviewSummariesMode.EXPLORER
            }
            fun page() = canvas.tabs.selectedComponent as Container
            fun named(name: String) = descendants(page()).single { it.name == name }
            fun texts() = descendants(page()).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                val area = descendants(page()).filterIsInstance<WrappedSummaryText>().single()
                area.select(0, "Updates".length)
                selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>()
                    .single { it.name == if (searchTab) "search-tab" else "search-selection" }.doClick()
                launcher.finish(launcher.searched.single(), 3,
                    SummaryMatch(folder.id, folder.identity, 0.9, 1),
                    SummaryMatch(matching.id, matching.identity, 0.8, 2),
                    SummaryMatch(rejected.id, rejected.identity, 0.1, 2))
                assertEquals("● Best match · 90%", (named("match:${folder.identity}") as JLabel).text)
                assertEquals(TraceStyle.BEST_MATCH, (named("summary:${folder.identity}") as JComponent).background)
                assertFalse(summaries.text(matching.id) in texts())
                val row = named("summary:${folder.identity}")
                row.dispatchEvent(MouseEvent(row, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                assertTrue(summaries.text(matching.id) in texts())
                assertEquals(!searchTab, summaries.text(rejected.id) in texts())
                assertEquals("● Related · 80%", (named("match:${matching.identity}") as JLabel).text)
                assertEquals(TraceStyle.RELATED, (named("summary:${matching.identity}") as JComponent).background)
                (named("summary-explorer-back") as javax.swing.JButton).doClick()
                assertEquals("● Best match · 90%", (named("match:${folder.identity}") as JLabel).text)
                (named("summaries-cancel-search") as javax.swing.JButton).doClick()
                assertTrue(summaries.text(0) in texts())
                assertTrue(descendants(page()).none { it.name?.startsWith("match:") == true })
            } finally { canvas.dispose() }
        }
    }

    fun testSearchAndSearchTabsHighlightResultsInEveryLayout() {
        val files = listOf("ui/Canvas.kt", "ui/Unrelated.kt", "docs/Readme.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Updates canvas and documentation."
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.ROOT) root else "Updates ${node.label}."
        })
        val folder = tree.nodes.single { it.path == "ui" }
        val matching = tree.nodes.single { it.path == "ui/Canvas.kt" }
        val rejected = tree.nodes.single { it.path == "ui/Unrelated.kt" }
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.filter { it != ReviewSummariesMode.EXPLORER }) for (searchTab in listOf(false, true)) {
            val launcher = FakeTraceLauncher()
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply {
                setSize(1600, 900)
                summariesView.apply {
                    this.mode = mode
                    update(tree)
                    if (mode == ReviewSummariesMode.HIERARCHY) selectLevel(0, tree)
                    if (mode == ReviewSummariesMode.DOCUMENT) selected = tree.root.identity
                }
            }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                layoutTree(canvas)
                val area = descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().singleOrNull { it.text == root }
                assertNotNull("$mode shows the root summary to search", area)
                val start = root.indexOf("canvas")
                area!!.select(start, start + "canvas".length)
                selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>()
                    .single { it.name == if (searchTab) "search-tab" else "search-selection" }.doClick()
                launcher.finish(launcher.searched.single(), 3,
                    SummaryMatch(folder.id, folder.identity, 0.9, 1),
                    SummaryMatch(matching.id, matching.identity, 0.8, 2),
                    SummaryMatch(rejected.id, rejected.identity, 0.1, 2))
                layoutTree(canvas)
                val page = canvas.tabs.selectedComponent as Container
                val texts = descendants(page).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
                assertTrue("$mode keeps the matching summary", summaries.text(matching.id) in texts)
                assertEquals("$mode hides rejected summaries only in search tabs", !searchTab, summaries.text(rejected.id) in texts)
                val badges = descendants(page).filterIsInstance<JLabel>()
                fun matchTexts(identity: String): List<String> {
                    val components = if (mode == ReviewSummariesMode.HIERARCHY) {
                        val card = descendants(page).single { it.name == if (identity.startsWith("file:")) identity else "node:$identity" } as Container
                        descendants(card).filter { it.name == "match" }
                    } else descendants(page).filter { it.name == "match:$identity" }
                    return components.mapNotNull { (it as? JLabel)?.text ?: (it as? javax.swing.text.JTextComponent)?.text }
                }
                assertTrue("$mode labels the best result", "● Best match · 90%" in matchTexts(folder.identity))
                assertTrue("$mode labels related results", "● Related · 80%" in matchTexts(matching.identity))
                if (!searchTab) assertTrue("$mode does not label rejected results", matchTexts(rejected.identity).isEmpty())
                val highlighted = descendants(page).filterIsInstance<JComponent>().filter { it.isOpaque }
                assertTrue("$mode highlights the best result", highlighted.any { it.background == TraceStyle.BEST_MATCH })
                assertTrue("$mode highlights related results", highlighted.any { it.background == TraceStyle.RELATED })
                assertTrue("$mode reports highlighting", badges.any { it.name == "trace-status" && it.text.contains("highlighted in the view") })
                if (searchTab) assertTrue("$mode reports that unrelated summaries are hidden", badges
                    .any { it.name == "trace-status" && it.text.contains("the rest are hidden") })
                (descendants(page).single { it.name == "trace-clear" } as com.intellij.ui.components.ActionLink).doClick()
                assertTrue("$mode clears result badges", descendants(page).none { it.name == "match" || it.name?.startsWith("match:") == true })
                assertTrue("$mode clears result backgrounds", highlighted.none { it.isOpaque && it.background in listOf(TraceStyle.BEST_MATCH, TraceStyle.RELATED) })
                assertEquals("Clearing highlights preserves filtering", !searchTab, summaries.text(rejected.id) in texts)

                // Cancel resets both kinds of filtering, even after Clear has removed the current trace.
                (descendants(page).single { it.name == "summaries-score" } as javax.swing.JButton).doClick()
                val scored = canvas.summariesView.scored.toMap()
                assertTrue(scored.isNotEmpty())
                val field = descendants(page).single { it.name == "summaries-search" } as com.intellij.ui.SearchTextField
                field.text = "no-such-summary"
                if (searchTab) {
                    val mainField = descendants(canvas.tabs.getComponentAt(0) as Container)
                        .single { it.name == "summaries-search" } as com.intellij.ui.SearchTextField
                    mainField.text = "Readme"
                }
                (descendants(page).single { it.name == "summaries-cancel-search" } as javax.swing.JButton).doClick()
                layoutTree(canvas)
                assertEquals("", field.text)
                assertSame(field, descendants(page).single { it.name == "summaries-search" })
                assertTrue("$mode restores rejected summaries", descendants(page).filterIsInstance<javax.swing.text.JTextComponent>()
                    .any { it.text == summaries.text(rejected.id) })
                assertTrue(descendants(page).none { it.name == "match" || it.name?.startsWith("match:") == true })
                assertTrue(descendants(page).none { it.name == "summaries-crumb:1" || it.name == "summaries-crumb:searching" })
                assertTrue(descendants(page).filterIsInstance<javax.swing.text.JTextComponent>()
                    .all { it.highlighter.highlights.isEmpty() && it.selectedText == null })
                assertEquals(scored, canvas.summariesView.scored)
                if (searchTab) {
                    assertEquals("Search", canvas.tabs.getTitleAt(1))
                    assertEquals("Summaries", canvas.tabs.getToolTipTextAt(1))
                    assertEquals("Cancel affects only its tab", "Readme", canvas.summariesView.fuzzy)
                } else {
                    assertNull(canvas.summariesView.selected)
                    assertNull(canvas.summariesView.reveal)
                    assertNull(canvas.summariesView.shownNodes(summaries))
                }
            } finally { canvas.dispose() }
        }
    }

    fun testCancelSearchCancelsPendingResultsAndLineNavigation() {
        val file = ReviewFile("ui/Canvas.kt", "/repo/ui/Canvas.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Paints the canvas border." })
        val leaf = tree.nodes.single { it.path == file.path }
        for (node in listOf(tree.root, leaf)) {
            val launcher = FakeTraceLauncher()
            val opened = mutableListOf<LineTrace>()
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher, openLines = { _, lines -> opened += lines }))
                .apply { setSize(1600, 900) }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                layoutTree(canvas)
                val row = descendants(canvas).single { it.name == "summary:${node.identity}" } as Container
                val area = descendants(row).filterIsInstance<javax.swing.text.JTextComponent>().first()
                area.select(0, "Paints".length)
                selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>()
                    .single { it.name == "search-selection" }.doClick()
                assertNotNull(canvas.summariesView.tracing)
                (descendants(canvas).single { it.name == "summaries-cancel-search" } as javax.swing.JButton).doClick()
                assertTrue(launcher.searchJobs.single().isCancelled)
                assertNull(canvas.summariesView.tracing)

                // A request may deliver its callback despite cancellation.
                if (node === leaf) {
                    val line = EvidenceLine(LineSide.AFTER, 1, "new", '+', 0, diff = true)
                    launcher.finishLines!!(Result.success(LineTrace(leaf.identity, leaf.path, "Paints",
                        listOf(LineMatch(listOf(line), 0.8)), listOf(line))))
                } else {
                    launcher.finish(launcher.searched.single(), 1, SummaryMatch(leaf.id, leaf.identity, 0.9, 1))
                }
                layoutTree(canvas)
                assertTrue(opened.isEmpty())
                assertNull(canvas.summariesView.tracing)
                assertNull(canvas.summariesView.selected)
                assertNull(canvas.summariesView.reveal)
                assertTrue(descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>()
                    .all { it.highlighter.highlights.isEmpty() && it.selectedText == null })
            } finally { canvas.dispose() }
        }
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
            tree.root.children.map { SummaryMatch(it, tree[it].identity, 0.1, 1) }, tree.root.children.size)
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

    fun testSummariesHierarchyTracesFoldedSummariesAndOpensThePathToTwoLevelsBelow() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt", "ui/hierarchy/Hierarchy.kt", "ui/hierarchy/Columns.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Deleted views: canvas, hierarchy."
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.ROOT) root else "Deletes ${node.label}."
        })
        fun node(path: String) = tree.nodes.single { it.path == path && it.kind != SummaryKind.ROOT }
        val canvasDir = node("ui/canvas")
        val hierarchyDir = node("ui/hierarchy")
        val hierarchyFile = node("ui/hierarchy/Hierarchy.kt")
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun note(identity: String) = panel().components.single { it.name == "summary:$identity" } as javax.swing.text.JTextComponent
        fun badges(identity: String) = descendants(panel().anchors.getValue(identity) as Container)
            .filterIsInstance<javax.swing.text.JTextComponent>().filter { it.name == "match" }.map { it.text }
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:hierarchy" }.doClick()
            layoutTree(canvas)
            // The canvas folder is open; the root folds to one row that shows its summary.
            assertTrue(canvasDir.identity in canvas.summariesView.expanded)
            click(descendants(panel()).single { it.name == "toggle:root" })
            assertEquals(root, note("root").text)

            // Scoring is available in the hierarchy too.
            (named("summaries-score").single() as javax.swing.JButton).doClick()
            layoutTree(canvas)
            assertTrue(launcher.scoredWords.isNotEmpty())
            val beforeHover = javax.swing.SwingUtilities.convertRectangle(note("root").parent, note("root").bounds, canvas)
            val sizeBeforeHover = panel().preferredSize
            pointAtWord(canvas, note("root"), "hierarchy", MouseEvent.MOUSE_MOVED)
            assertEquals(beforeHover, javax.swing.SwingUtilities.convertRectangle(note("root").parent, note("root").bounds, canvas))
            assertEquals(sizeBeforeHover, panel().preferredSize)
            assertEquals("hierarchy", canvas.summariesView.tracing!!.word.text)
            assertEquals(3, note("root").highlighter.highlights.size)
            assertTrue((named("trace-status").single() as JLabel).text.contains("Right-click a scored word"))
            searchScoredWord(canvas, note("root"), "hierarchy")
            val context = launcher.searched.single()
            launcher.finish(context, 6,
                SummaryMatch(hierarchyFile.id, hierarchyFile.identity, 0.9, 2),
                SummaryMatch(hierarchyDir.id, hierarchyDir.identity, 0.6, 1),
                SummaryMatch(canvasDir.id, canvasDir.identity, 0.7, 1),
                SummaryMatch(node("ui/canvas/Paint.kt").id, "file:ui/canvas/Paint.kt", 0.2, 2))
            layoutTree(canvas)

            // The traced folder and the folders leading to deeper matches open; the related canvas folder, with nothing
            // related beneath, folds so its summary shows.
            assertTrue("root" in canvas.summariesView.expanded)
            assertTrue(hierarchyDir.identity in canvas.summariesView.expanded)
            assertFalse(canvasDir.identity in canvas.summariesView.expanded)
            assertEquals("Deletes ${canvasDir.label}.", note(canvasDir.identity).text)
            // Matches are tinted and labelled; the best is selected.
            assertEquals(listOf("● Best match · 90%"), badges(hierarchyFile.identity))
            assertEquals(listOf("● Related · 60%"), badges(hierarchyDir.identity))
            assertEquals(listOf("● Related · 70%"), badges(canvasDir.identity))
            assertTrue(badges("root").isEmpty())
            assertEquals(TraceStyle.BEST_MATCH, note(hierarchyFile.identity).background)
            assertTrue(note(hierarchyFile.identity).isOpaque)
            assertFalse(note("file:ui/hierarchy/Columns.kt").isOpaque)
            assertEquals(hierarchyFile.identity, canvas.summariesView.selected)
            assertTrue((named("trace-status").single() as JLabel).text.contains("3 of 6 summaries"))

            // Moving away from completed search results also clears their decorations in place.
            val searchedPanel = panel()
            val searchedNote = note(canvasDir.identity)
            val searchedBounds = searchedNote.bounds
            val searchedSize = panel().preferredSize
            pointAtWord(canvas, searchedNote, "Deletes", MouseEvent.MOUSE_MOVED)
            assertSame(searchedPanel, panel())
            assertSame(searchedNote, note(canvasDir.identity))
            assertEquals(searchedBounds, searchedNote.bounds)
            assertEquals(searchedSize, panel().preferredSize)
            assertTrue(badges(hierarchyFile.identity).isEmpty())
            assertFalse(note(hierarchyFile.identity).isOpaque)

            // Clearing drops the marks but keeps the folding and the saved contexts.
            (named("trace-clear").single() as com.intellij.ui.components.ActionLink).doClick()
            layoutTree(canvas)
            assertNull(canvas.summariesView.tracing)
            assertTrue(badges(hierarchyFile.identity).isEmpty())
            assertTrue(hierarchyDir.identity in canvas.summariesView.expanded)
            assertTrue(canvas.summariesView.scored.isNotEmpty())
        } finally {
            canvas.dispose()
        }
    }

    fun testSummaryContextMenuScoresOnlyThatSummary() = ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.forEach { mode ->
        val tree = buildSummaryTree(listOf("Canvas.kt", "Paint.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        })
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Paints the canvas border." })
        val file = tree.nodes.single { it.path == "Paint.kt" }
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher))
            .apply {
                setSize(1600, 900); summariesView.mode = mode; summariesView.visibleOnly = true
                if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse(tree[file.parent!!].identity, tree)
            }
        fun area(node: SummaryNode): javax.swing.text.JTextComponent {
            val name = when {
                mode != ReviewSummariesMode.DOCUMENT -> "summary:${node.identity}"
                node.kind == SummaryKind.FILE -> "summary:${node.path}"
                else -> "card-summary:${node.identity}"
            }
            val component = descendants(canvas).single { it.name == name }
            return component as? javax.swing.text.JTextComponent ?: descendants(component as Container).filterIsInstance<javax.swing.text.JTextComponent>()
                .single { it.text == summaries.text(node.id) }
        }
        fun scoreItem(area: javax.swing.text.JTextComponent) = selectionMenu(area)!!.components
            .filterIsInstance<javax.swing.JMenuItem>().single { it.name == "score-summary" }
        fun expected(node: SummaryNode) = SummaryWord.split(summaries.text(node.id)!!)
            .filter { it.meaningful }.map { node.id to it.index }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            val fileArea = area(file)
            assertTrue("An unscored summary offers Score", scoreItem(fileArea).isEnabled)
            canvas.summariesView.scoring = true
            assertFalse("Score is disabled during another scoring run", scoreItem(fileArea).isEnabled)
            canvas.summariesView.scoring = false
            val first = SummaryWord.split(fileArea.text).first()
            canvas.summariesView.scored[file.identity to first.index] = SummaryContext(
                file.identity, fileArea.text, first, listOf(first), first.text)
            fileArea.select(fileArea.text.indexOf("canvas"), fileArea.text.indexOf("canvas") + "canvas".length)
            assertTrue("Score remains available with selected text", scoreItem(fileArea).isEnabled)
            scoreItem(fileArea).doClick()
            assertEquals(expected(file).filter { it.second != first.index }, launcher.scoredWords)
            assertEquals(setOf(file.identity), canvas.summariesView.scored.values.map { it.identity }.toSet())
            assertFalse("A fully scored summary disables Score", scoreItem(fileArea).isEnabled)
            assertTrue(launcher.searched.isEmpty())
            assertTrue(launcher.searchedLines.isEmpty())

            if (mode == ReviewSummariesMode.HIERARCHY) {
                descendants(canvas).single { it.name == "toggle:root" }.let { toggle ->
                    toggle.dispatchEvent(MouseEvent(toggle, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                }
                layoutTree(canvas)
            }
            if (mode == ReviewSummariesMode.DOCUMENT) {
                descendants(canvas).single { it.name == "node:root" }.let { rail ->
                    rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                }
                layoutTree(canvas)
            }
            launcher.scoredWords.clear()
            if (mode == ReviewSummariesMode.EXPLORER)
                (descendants(canvas).single { it.name == "summary-explorer-back" } as javax.swing.JButton).doClick()
            scoreItem(area(tree.root)).doClick()
            assertEquals("Scoring a parent must not score its descendants", expected(tree.root), launcher.scoredWords)
            assertEquals(setOf(file.identity, tree.root.identity), canvas.summariesView.scored.values.map { it.identity }.toSet())
        } finally { canvas.dispose() }
    }

    fun testFileSummaryHighlightsSearchAndOpenTheFileLines() = ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.forEach { mode ->
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Paints the canvas border." })
        val file = tree.nodes.single { it.path == "ui/canvas/Paint.kt" }
        val launcher = FakeTraceLauncher()
        val opened = mutableListOf<Pair<SummaryNode, LineTrace>>()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher, openLines = { node, lines -> opened += node to lines }))
            .apply {
                setSize(1600, 900); summariesView.mode = mode; summariesView.visibleOnly = true
                if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse(tree[file.parent!!].identity, tree)
            }
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun area(): javax.swing.text.JTextComponent {
            val component = named("summary:${if (mode == ReviewSummariesMode.DOCUMENT) file.path else file.identity}").single()
            return component as? javax.swing.text.JTextComponent ?: descendants(component as Container).filterIsInstance<javax.swing.text.JTextComponent>().first()
        }
        fun status() = (named("trace-status").single() as JLabel).text
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            // Document files remain visible even when their folders are folded in the other modes.
            if (mode == ReviewSummariesMode.DOCUMENT) canvas.summariesView.expanded.clear()
            (named("summaries-score").single() as javax.swing.JButton).doClick()
            layoutTree(canvas)
            assertTrue("File summaries are scored", launcher.scoredWords.any { (id, _) -> id == file.id })

            pointAtWord(canvas, area(), "border", MouseEvent.MOUSE_MOVED)
            assertEquals("ui/canvas/Paint.kt", canvas.summariesView.tracing!!.file)
            assertEquals(3, area().highlighter.highlights.size)
            assertTrue(status().contains("find its lines in Paint.kt"))
            val unscoredBox = area().modelToView2D(area().text.indexOf("the") + 1)
            assertTrue("A pointed menu on an unscored word must not fall back to the traced word",
                selectionMenu(area(), Point(unscoredBox.centerX.toInt(), unscoredBox.centerY.toInt()))!!
                    .components.none { it.name == "search-selection" })

            pointAtWord(canvas, area(), "border", MouseEvent.MOUSE_CLICKED)
            assertTrue("Clicking a scored word must not search its lines", launcher.searchedLines.isEmpty())
            searchScoredWord(canvas, area(), "border")
            val context = launcher.searchedLines.single()
            assertTrue("A file summary searches its lines, not summaries", launcher.searched.isEmpty())
            assertTrue(status().contains("Searching the lines of Paint.kt"))
            val line = EvidenceLine(LineSide.AFTER, 1, "new", '+', 0, diff = true)
            launcher.finishLines!!(Result.success(LineTrace(file.identity, file.path, context.query, listOf(LineMatch(listOf(line), 0.8)), listOf(line))))
            layoutTree(canvas)
            assertEquals(file.identity, opened.single().first.identity)
            assertEquals(listOf(1), opened.single().second.after)
            assertTrue(status(), status().contains("Best match at Paint.kt:1 · 80%"))

            // Choosing Search on a highlight again reopens the found lines without searching again.
            searchScoredWord(canvas, area(), "border")
            assertEquals(2, opened.size)
            assertEquals(1, launcher.searchedLines.size)

            // Switching scopes on the same word must replace the query, even inside an existing highlight.
            val word = SummaryWord.split(area().text).single { it.text == "border" }
            val box = area().modelToView2D(word.start + 1)
            selectionMenu(area(), Point(box.centerX.toInt(), box.centerY.toInt()))!!.components
                .filterIsInstance<javax.swing.JMenuItem>().single { it.name == "search-word" }.doClick()
            assertEquals("border", launcher.searchedLines.last().query)
            searchScoredWord(canvas, area(), "border")
            assertEquals(context, launcher.searchedLines.last())
            assertEquals(3, launcher.searchedLines.size)
        } finally {
            canvas.dispose()
        }
    }

    fun testWordMenuOffersIndependentSearchScopes() {
        val summary = "Paints the canvas border."
        val words = SummaryWord.split(summary)
        val word = words.single { it.text == "canvas" }
        val state = ChangeSummariesViewState()
        val scored = SummaryContext("root", summary, word, words, "Paints the canvas border")
        state.scored["root" to word.index] = scored
        val area = javax.swing.JTextArea(summary).apply { setSize(400, 100) }
        val searches = mutableListOf<String>()
        val traces = mutableListOf<Int>()
        installWordTracing(area, "root", summary, state,
            trace = { _, index, search -> if (search) traces += index },
            searchSelection = { identity, start, end -> searches += selectionContext(identity, summary, start, end)!!.query })
        area.select(word.start, words.last().end)
        val box = area.modelToView2D(word.start + 1)
        val point = Point(box.centerX.toInt(), box.centerY.toInt())
        val items = selectionMenu(area, point)!!.components.filterIsInstance<javax.swing.JMenuItem>()
        assertEquals(listOf("Search current word", "Search whole scored query", "Search selected query"), items.map { it.text })
        items.forEach { it.doClick() }
        assertEquals(listOf("canvas", "canvas border"), searches)
        assertEquals(listOf(word.index), traces)

        // Word-only search stays available without a score or selection.
        state.scored.clear()
        area.select(word.start, word.start)
        val unscored = selectionMenu(area, point)!!.components.filterIsInstance<javax.swing.JMenuItem>()
        assertEquals(listOf("search-word"), unscored.map { it.name })
        unscored.single().doClick()
        assertEquals("canvas", searches.last())
        assertNull("Blank space must not fall back to another word", selectionMenu(area, Point(390, 80)))
    }

    fun testHoveredAndSelectedWordsEchoInSummariesBelow() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt", "docs/Guide.md").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val parent = tree.root
        val child = tree.nodes.single { it.path == "ui/canvas/Paint.kt" }
        val summary = "Paints the canvas border and the frame."
        val childSummary = "Draws the Canvas border twice."
        val state = ChangeSummariesViewState()
        val areas = mutableListOf<javax.swing.text.JTextComponent>()
        state.echoTree = { tree }
        state.echoChanged = { areas.forEach(::updateWordTracing) }
        val area = javax.swing.JTextArea(summary).apply { setSize(400, 100) }
        val below = javax.swing.JTextArea(childSummary).apply { setSize(400, 100) }
        installWordTracing(area, parent.identity, summary, state, trace = { _, _, _ -> })
        installWordTracing(below, child.identity, childSummary, state, trace = { _, _, _ -> })
        areas += listOf(area, below)
        fun echoed() = below.highlighter.highlights.filter {
            (it.painter as? javax.swing.text.DefaultHighlighter.DefaultHighlightPainter)?.color == TraceStyle.ECHO_WORD
        }.map { childSummary.substring(it.startOffset, it.endOffset) }
        fun hover(text: String) {
            val word = SummaryWord.split(summary).single { it.text == text }
            val box = area.modelToView2D(word.start + 1)
            val event = MouseEvent(area, MouseEvent.MOUSE_MOVED, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 0, false)
            area.mouseMotionListeners.forEach { it.mouseMoved(event) }
        }

        // Without a score, the hovered word alone recurs below, regardless of case.
        hover("canvas")
        assertEquals(listOf("Canvas"), echoed())

        // A scored word echoes its whole context.
        val words = SummaryWord.split(summary)
        val border = words.single { it.text == "border" }
        state.scored[parent.identity to border.index] =
            SummaryContext(parent.identity, summary, border, words.filter { it.text in setOf("canvas", "border") }, "canvas border")
        hover("border")
        assertEquals(listOf("Canvas", "border"), echoed())

        // A selection echoes its meaningful words until it is cleared; summaries outside the subtree never echo.
        area.select(summary.indexOf("frame"), summary.indexOf("frame") + 5)
        assertEquals(emptyList<String>(), echoed())
        hover("canvas")
        assertEquals("Hovering keeps the selection's echo", emptyList<String>(), echoed())
        area.select(0, summary.indexOf(" border"))
        assertEquals(listOf("Canvas"), echoed())
        area.select(0, 0)
        assertNull(state.echo)
        val sibling = tree.nodes.single { it.path == "docs/Guide.md" }
        state.echo(child.identity, SummaryWord.split(childSummary))
        assertFalse(sibling.identity in state.echo!!.below)
        assertFalse(state.echo!!.reaches(sibling.identity))
        state.clearEcho()

        // Hovering a file's word echoes it in its folders up to the root, but not in the hovered summary itself.
        fun echoedAbove() = area.highlighter.highlights.filter {
            (it.painter as? javax.swing.text.DefaultHighlighter.DefaultHighlightPainter)?.color == TraceStyle.ECHO_WORD
        }.map { summary.substring(it.startOffset, it.endOffset) }
        val folder = tree.nodes.single { it.path == "ui/canvas" }
        val folderSummary = "Canvas drawing and its border."
        val middle = javax.swing.JTextArea(folderSummary).apply { setSize(400, 100) }
        installWordTracing(middle, folder.identity, folderSummary, state, trace = { _, _, _ -> })
        areas += middle
        val childWord = SummaryWord.split(childSummary).single { it.text == "border" }
        val box = below.modelToView2D(childWord.start + 1)
        val event = MouseEvent(below, MouseEvent.MOUSE_MOVED, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 0, false)
        below.mouseMotionListeners.forEach { it.mouseMoved(event) }
        assertEquals(listOf("border"), echoedAbove())
        assertEquals(1, middle.highlighter.highlights.count {
            (it.painter as? javax.swing.text.DefaultHighlighter.DefaultHighlightPainter)?.color == TraceStyle.ECHO_WORD
        })
        assertEquals(emptyList<String>(), echoed())
        assertTrue(state.echo!!.reaches(parent.identity))
        assertFalse(state.echo!!.reaches(sibling.identity))
    }

    fun testSelectedSummaryTextSearchesWithoutScoring() =ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.forEach { mode ->
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Paints the canvas border." })
        val file = tree.nodes.single { it.path == "ui/canvas/Paint.kt" }
        val launcher = FakeTraceLauncher()
        val opened = mutableListOf<Pair<SummaryNode, LineTrace>>()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher, openLines = { node, lines -> opened += node to lines }))
            .apply {
                setSize(1600, 900); summariesView.mode = mode
                if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse(tree[file.parent!!].identity, tree)
            }
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun area(identity: String, path: String): javax.swing.text.JTextComponent {
            val component = named("summary:${if (mode == ReviewSummariesMode.DOCUMENT) path else identity}").single()
            return component as? javax.swing.text.JTextComponent ?: descendants(component as Container).filterIsInstance<javax.swing.text.JTextComponent>().first()
        }
        fun fileArea() = area(file.identity, file.path)
        fun status() = (named("trace-status").single() as JLabel).text
        fun search(area: javax.swing.text.JTextComponent, text: String) {
            area.select(area.text.indexOf(text), area.text.indexOf(text) + text.length)
            val item = selectionMenu(area)!!.components.filterIsInstance<javax.swing.JMenuItem>().single { it.name == "search-selection" }
            assertTrue(item.isEnabled)
            item.doClick()
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            if (mode == ReviewSummariesMode.DOCUMENT) canvas.summariesView.expanded.clear()
            assertTrue((named("scoring-status").single() as JLabel).text.contains("right-click Search"))
            assertEquals("The caret word can be searched without scoring", listOf("score-summary", "search-word"),
                selectionMenu(fileArea())!!.components.mapNotNull { it.name }.filter { it == "score-summary" || it.startsWith("search-") })

            // A right click neither folds nor selects the row.
            val expanded = canvas.summariesView.expanded.toSet()
            fileArea().dispatchEvent(MouseEvent(fileArea(), MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON3))
            layoutTree(canvas)
            assertEquals(expanded, canvas.summariesView.expanded)
            assertNull(canvas.summariesView.selected)

            // A file summary's selection searches its lines and opens the best, with nothing scored.
            search(fileArea(), "canvas border")
            assertTrue(launcher.scoredWords.isEmpty())
            assertEquals("canvas border", launcher.searchedLines.single().query)
            assertTrue(status(), status().contains("Searching the lines of Paint.kt"))
            assertTrue(fileArea().highlighter.highlights.isNotEmpty())
            val line = EvidenceLine(LineSide.AFTER, 1, "new", '+', 0, diff = true)
            launcher.finishLines!!(Result.success(LineTrace(file.identity, file.path, "canvas border", listOf(LineMatch(listOf(line), 0.8)), listOf(line))))
            layoutTree(canvas)
            assertEquals(file.identity, opened.single().first.identity)
            assertTrue(status(), status().contains("Best match at Paint.kt:1 · 80%"))

            // A summary with summaries below searches them instead.
            if (mode == ReviewSummariesMode.OUTLINE) {
                search(area("root", tree.root.path), "Paints")
                assertEquals("Paints", launcher.searched.single().query)
                assertTrue(status(), status().contains("Searching the summaries"))
                assertTrue(launcher.scoredWords.isEmpty())
            }
        } finally {
            canvas.dispose()
        }
    }

    fun testTopicFileSummaryHighlightsSearchAndOpenTheFileLines() = ChangeTopicsMode.entries.forEach { mode ->
        val source = ReviewFile("ui/Paint.kt", "/repo/ui/Paint.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        val tree = buildSummaryTree(listOf(source))
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Paints the canvas border." })
        val card = TopicsCard(ReviewTopics(listOf(source)).assign(source.path, "UI>Painting"), summaries, canRun = true)
        val launcher = FakeTraceLauncher()
        val opened = mutableListOf<Pair<SummaryNode, LineTrace>>()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher, openLines = { node, lines -> opened += node to lines }))
            .apply { setSize(1600, 900); topicsView.mode = mode; topicsView.visibleOnly = true }
        fun named(name: String) = descendants(canvas).single { it.name == name }
        fun area(): javax.swing.text.JTextComponent {
            val component = named("summary:${source.path}")
            return component as? javax.swing.text.JTextComponent ?: descendants(component as Container).filterIsInstance<javax.swing.text.JTextComponent>().first()
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = card))
            layoutTree(canvas)
            if (mode == ChangeTopicsMode.DOCUMENT) canvas.topicsView.selectLevel(0, topicOutline(card.topics))
            (named("topics-score") as javax.swing.JButton).doClick()
            layoutTree(canvas)
            assertTrue(launcher.scoredWords.any { (id, _) -> card.reading.tree[id].kind == SummaryKind.FILE })
            pointAtWord(canvas, area(), "border", MouseEvent.MOUSE_MOVED)
            assertEquals(source.path, canvas.topicsView.tracing!!.file)
            assertEquals(3, area().highlighter.highlights.size)
            assertTrue(launcher.searchedLines.isEmpty())
            pointAtWord(canvas, area(), "border", MouseEvent.MOUSE_CLICKED)
            assertTrue("Clicking a scored word must not search its lines", launcher.searchedLines.isEmpty())
            searchScoredWord(canvas, area(), "border")
            val context = launcher.searchedLines.single()
            assertTrue(launcher.searched.isEmpty())
            val line = EvidenceLine(LineSide.AFTER, 1, "new", '+', 0, diff = true)
            launcher.finishLines!!(Result.success(LineTrace(context.identity, source.path, context.query,
                listOf(LineMatch(listOf(line), 0.8)), listOf(line))))
            layoutTree(canvas)
            assertEquals(context.identity, opened.single().first.identity)
            assertEquals(listOf(1), opened.single().second.after)
            assertTrue((named("trace-status") as JLabel).text.contains("Best match at Paint.kt:1"))
            searchScoredWord(canvas, area(), "border")
            assertEquals(2, opened.size)
            assertEquals(1, launcher.searchedLines.size)
        } finally { canvas.dispose() }
    }

    fun testTopicsHierarchyScoresAndTracesFoldedTopicSummaries() {
        val files = listOf("src/wallet.kt", "src/auth.kt", "ui/form.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val topics = ReviewTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val topicTree = buildTopicSummaryTree(topics)
        val topicSummaries = ReviewSummaries(topicTree).summarize(topicTree.nodes.associate { node ->
            node.id to if (node.identity == "topic:storage") "Adds user wallets and token auth." else "Changes ${node.label}."
        })
        val shared = ReviewSummaries(buildSummaryTree(files))
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = launcher)).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().single()
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun note(name: String) = panel().components.single { it.name == "summary:$name" } as javax.swing.text.JTextComponent
        fun badges(identity: String) = descendants(panel().anchors.getValue(identity) as Container)
            .filterIsInstance<javax.swing.text.JTextComponent>().filter { it.name == "match" }.map { it.text }
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            val card = TopicsCard(topics, shared, canRun = true, topicSummaries)
            canvas.showNotes(CanvasState(emptyList(), topics = card))
            layoutTree(canvas)
            assertTrue("Scoring is available in the document too", named("topics-score").isNotEmpty())
            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.text == "Hierarchy" }.doClick()
            layoutTree(canvas)
            click(descendants(panel()).single { it.name == "toggle:0:storage" })
            assertEquals("Adds user wallets and token auth.", note("0:storage").text)

            (named("topics-score").single() as javax.swing.JButton).doClick()
            layoutTree(canvas)
            // The hidden root is not scored; topics and their subtopics are, through the hierarchy's own tree.
            val reading = card.reading
            assertTrue(launcher.scoredWords.none { (id, _) -> reading.tree[id].kind == SummaryKind.ROOT })
            assertTrue(launcher.scoredWords.any { (id, _) -> reading.tree[id].identity == "topic:storage" })
            pointAtWord(canvas, note("0:storage"), "auth", MouseEvent.MOUSE_MOVED)
            assertEquals("auth", canvas.topicsView.tracing!!.word.text)
            assertEquals(3, note("0:storage").highlighter.highlights.size)
            searchScoredWord(canvas, note("0:storage"), "auth")
            val context = launcher.searched.single()
            fun match(identity: String, probability: Double, levels: Int) =
                SummaryMatch(reading.tree.nodes.single { it.identity == identity }.id, identity, probability, levels)
            launcher.finish(context, 3, match("topic:storage > user > auth", 0.8, 2), match("topic:storage > user > wallet", 0.1, 2))
            layoutTree(canvas)

            // Storage and its user topic open down to the match two levels below; the match folds to show its summary.
            val outline = panel().outline
            fun topic(identity: String) = outline.nodes.single { it.identity == identity }
            assertFalse(canvas.topicsView.isCollapsed(topic("storage")))
            assertFalse(canvas.topicsView.isCollapsed(topic("storage > user")))
            assertTrue(canvas.topicsView.isCollapsed(topic("storage > user > auth")))
            assertEquals("Changes Auth.", note("2:storage > user > auth").text)
            assertEquals(TraceStyle.BEST_MATCH, note("2:storage > user > auth").background)
            assertEquals(listOf("● Best match · 80%"), badges("topic:storage > user > auth"))
            assertTrue(badges("topic:storage > user > wallet").isEmpty())
            assertTrue(canvas.topicsView.isSelected(topic("storage > user > auth")))
            assertTrue((named("trace-status").single() as JLabel).text.contains("1 of 3 summaries"))

            // A new snapshot that changes the traced summary drops the trace.
            val rewritten = topicSummaries.summarize(mapOf(topicTree.nodes.single { it.identity == "topic:storage" }.id to "Rewritten."))
            canvas.updateTopics(TopicsCard(topics, shared, canRun = true, rewritten))
            layoutTree(canvas)
            assertNull(canvas.topicsView.tracing)
            assertTrue(badges("topic:storage > user > auth").isEmpty())
        } finally {
            canvas.dispose()
        }
    }

    fun testSummaryHoverAndClearPreserveScrolledPositionWithLongQueries() {
        val files = (0..14).flatMap { folder -> (0..1).map { file ->
            val path = "pkg/folder${folder.toString().padStart(2, '0')}/File$file.kt"
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        } }
        val tree = buildSummaryTree(files)
        val summary = ("Handles important requests. " + "Additional explanation of the behavior. ".repeat(24)).trim()
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to summary })
        val target = tree.nodes.single { it.path == "pkg/folder13" }
        val words = SummaryWord.split(summary)
        val word = words.single { it.text == "important" }
        val nextWord = words.first { it.text == "Additional" }
        val query = "A long hover query that must never wrap into extra status rows. ".repeat(30)
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }) {
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(traceSummaries = FakeTraceLauncher())).apply { setSize(1000, 700) }
            canvas.summariesView.apply {
                this.mode = mode
                update(tree)
                selectLevel(99, tree)
                if (mode == ReviewSummariesMode.HIERARCHY) toggle(target.identity)
                if (mode == ReviewSummariesMode.DOCUMENT) selected = target.identity
                if (mode == ReviewSummariesMode.EXPLORER) browse(tree[target.parent!!].identity, tree)
                scored[target.identity to word.index] = SummaryContext(target.identity, summary, word, listOf(word), query)
                scored[target.identity to nextWord.index] = SummaryContext(target.identity, summary, nextWord, listOf(nextWord), "Another query")
            }
            fun area(): javax.swing.text.JTextComponent {
                val row = descendants(canvas).single {
                    it.name == if (mode == ReviewSummariesMode.DOCUMENT) "card-summary:${target.identity}" else "summary:${target.identity}"
                }
                return if (row is javax.swing.text.JTextComponent) row else descendants(row as Container).filterIsInstance<javax.swing.text.JTextComponent>().single { it.text == summary }
            }
            fun scroll() = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, area()) as javax.swing.JScrollPane
            fun bounds() = javax.swing.SwingUtilities.convertRectangle(area().parent, area().bounds, canvas)
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
                layoutTree(canvas)
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                area().scrollRectToVisible(java.awt.Rectangle(0, 0, area().width, area().height))
                assertTrue("The test must hover a scrolled summary in $mode", scroll().viewport.viewPosition.y > 0)
                val position = Point(scroll().viewport.viewPosition)
                val originalArea = area()
                val originalScroll = scroll()
                val page = canvas.tabs.selectedComponent as javax.swing.JScrollPane
                val pagePosition = Point(page.viewport.viewPosition)
                val before = bounds()
                val size = canvas.preferredSize
                pointAtWord(canvas, area(), "important", MouseEvent.MOUSE_MOVED)
                // Check before queued scroll restoration can conceal a temporary jump.
                assertSame("Hover must retain the text component in $mode", originalArea, area())
                assertSame("Hover must retain the scroll pane in $mode", originalScroll, scroll())
                assertEquals(position, scroll().viewport.viewPosition)
                assertEquals(pagePosition, page.viewport.viewPosition)
                assertNotNull("Hover must select a scored word in $mode (${area().text})", canvas.summariesView.tracing)
                assertEquals("Hover must paint the scored word in $mode", 1, area().highlighter.highlights.size)
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                assertEquals("Hover must preserve the viewport in $mode", position, scroll().viewport.viewPosition)
                assertEquals("Hover must keep the text under the pointer in $mode", before, bounds())
                assertEquals(size, canvas.preferredSize)
                val status = descendants(canvas).single { it.name == "trace-status" } as JLabel
                assertTrue(status.text.contains(query))
                assertEquals(status.text, status.toolTipText)
                pointAtWord(canvas, area(), "Additional", MouseEvent.MOUSE_MOVED)
                assertSame(originalArea, area())
                assertEquals(position, scroll().viewport.viewPosition)
                assertEquals(nextWord.start, area().highlighter.highlights.single().startOffset)
                assertTrue(status.text.contains("Another query"))
                (descendants(canvas).single { it.name == "trace-clear" } as com.intellij.ui.components.ActionLink).doClick()
                layoutTree(canvas)
                assertSame("Clear must retain the text component in $mode", originalArea, area())
                assertSame(originalScroll, scroll())
                assertEquals(position, scroll().viewport.viewPosition)
                assertEquals(0, area().highlighter.highlights.size)
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                assertEquals(position, scroll().viewport.viewPosition)
                assertEquals(before, bounds())
                assertEquals(size, canvas.preferredSize)
                pointAtWord(canvas, area(), "important", MouseEvent.MOUSE_MOVED)
                assertEquals("The same word can be hovered again after Clear", 1, area().highlighter.highlights.size)
                assertEquals(position, scroll().viewport.viewPosition)
            } finally { canvas.dispose() }
        }
    }

    fun testSummariesShowOnlyFileNamesWithMetadataInTooltipsAndSummaryClicksToggleChildren() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/hierarchy/Hierarchy.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Summary of ${it.identity}." })
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        fun texts(identity: String) = descendants(descendants(canvas).single { it.name == "summary:$identity" } as Container)
            .filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
        fun header(identity: String) = descendants(canvas).single { it.name == "summary-name:$identity" } as JLabel
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            assertEquals(listOf("Summary of file:ui/canvas/Canvas.kt."), texts("file:ui/canvas/Canvas.kt"))
            assertTrue(descendants(canvas).none { it.name == "summaries-show-names" })
            assertEquals("./ · All changes · 2 files", header("root").text)
            assertTrue(header("root").toolTipText.contains("/repo/"))
            val fileHeader = header("file:ui/canvas/Canvas.kt")
            assertEquals("Canvas.kt", fileHeader.text)
            assertTrue(fileHeader.toolTipText.contains("modified file"))
            assertTrue(fileHeader.toolTipText.contains("ui/canvas/Canvas.kt"))
            assertTrue(fileHeader.toolTipText.contains("/repo/ui/canvas/Canvas.kt"))
            val rootSummary = descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().single { it.text == "Summary of root." }
            click(rootSummary)
            assertFalse("root" in canvas.summariesView.expanded)
            assertTrue(descendants(canvas).none { it.name == "summary:file:ui/canvas/Canvas.kt" })
            assertTrue(descendants(canvas).none { it.name?.startsWith("details:") == true })
            click(header("root"))
            assertTrue("root" in canvas.summariesView.expanded)
            val fileRow = descendants(canvas).single { it.name == "summary:file:ui/canvas/Canvas.kt" } as Container
            descendants(fileRow).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "View diff" }.doClick()
            assertEquals(listOf("ui/canvas/Canvas.kt"), opened)
            canvas.updateSummaries(SummariesCard(summaries, running = true, canRun = false))
            layoutTree(canvas)
            assertEquals(fileHeader.text, header("file:ui/canvas/Canvas.kt").text)
        } finally {
            canvas.dispose()
        }
    }

    fun testExplainLinksAskTheChatAboutEveryEntryInEverySummariesView() = ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }.forEach { mode ->
        val files = listOf("ui/canvas/Canvas.kt", "ui/hierarchy/Hierarchy.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Summary of ${it.identity}." })
        val explained = mutableListOf<String>()
        val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(explainNode = { explained += it.identity })).apply { setSize(1600, 900) }
        // The split view keeps entry actions in each tree row's context menu.
        fun links(): List<javax.swing.AbstractButton> = (descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>() +
            descendants(canvas).mapNotNull { (it as? JComponent)?.componentPopupMenu }.distinct()
                .flatMap { it.components.filterIsInstance<javax.swing.JMenuItem>() }).filter { it.text == "Explain" }
        try {
            canvas.summariesView.mode = mode
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true)))
            layoutTree(canvas)
            assertTrue(mode.name, links().isNotEmpty())
            assertTrue(mode.name, links().all { it.isEnabled })
            links().forEach { it.doClick() }
            assertTrue(mode.name, explained.isNotEmpty())
            assertTrue(mode.name, explained.all { identity -> tree.nodes.any { it.identity == identity } })
            if (mode != ReviewSummariesMode.EXPLORER) assertTrue(mode.name, "file:ui/canvas/Canvas.kt" in explained)
            canvas.updateSummaries(SummariesCard(summaries, running = true, canRun = false))
            layoutTree(canvas)
            assertTrue(mode.name, links().none { it.isEnabled })
        } finally {
            canvas.dispose()
        }
    }

    fun testExplanationRemainsAccessibleAcrossCanvasUpdatesAndOpensItsChart() {
        var chartOpened = 0
        val chart = dev.insight.callgraph.ExplainChartResult.Ready(
            dev.insight.callgraph.ExplainChart(emptyList(), "{}") { chartOpened++ })
        val result = ExplainResult("/explain src/a.kt", "The cache avoids repeated reads.", running = false, chart)
        val canvas = canvas()
        try {
            canvas.showNotes(CanvasState(listOf(note), explanation = result))
            canvas.focusExplanation()
            assertEquals("Explanation", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            assertTrue(descendants(canvas.tabs.selectedComponent as Container)
                .filterIsInstance<javax.swing.text.JTextComponent>().any { it.text == result.text })
            descendants(canvas).filterIsInstance<javax.swing.JButton>().single { it.text == "Open Flow Chart" }.doClick()
            assertEquals(1, chartOpened)
            canvas.showNotes(CanvasState(listOf(note.copy(text = "Updated note")), explanation = result))
            assertEquals("Explanation", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            canvas.updateSummaries(null)
            canvas.updateTopics(null)
            canvas.updateFileView(null)
            assertEquals("Explanation", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            canvas.focusNote(note)
            assertEquals("Note · a.kt", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            canvas.focusExplanation()
            assertEquals("Explanation", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            canvas.showNotes(CanvasState(listOf(note)))
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals("Note · a.kt", canvas.tabs.getTitleAt(0))
        } finally { canvas.dispose() }
    }

    fun testExplanationShowsProgressAndWhyTheChartIsUnavailable() {
        val canvas = canvas()
        try {
            val result = ExplainResult("/explain", "Explaining…", running = true)
            canvas.showNotes(CanvasState(emptyList(), explanation = result))
            assertEquals("Explaining…", canvas.tabs.getTitleAt(0))
            canvas.showNotes(CanvasState(emptyList(), explanation = result.copy(text = "Stopped.", running = false,
                chart = dev.insight.callgraph.ExplainChartResult.Unavailable("the graph is building."))))
            assertEquals("Explanation", canvas.tabs.getTitleAt(0))
            val texts = descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().map { it.text }
            assertTrue("Stopped." in texts)
            assertTrue("Flow Chart unavailable: the graph is building." in texts)
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.JButton>().none { it.text == "Open Flow Chart" })
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
