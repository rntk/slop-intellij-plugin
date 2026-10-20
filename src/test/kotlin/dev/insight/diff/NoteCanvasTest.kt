package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JLabel
import kotlinx.coroutines.Job

class NoteCanvasTest : BasePlatformTestCase() {
    private val note = DiffNote(listOf("src/a.kt"), "A short explanation.")

    fun testTabsKeepSelectionAcrossUpdatesAndFocusSelectsTheRequestedBlock() {
        val other = DiffNote(listOf("src/b.kt"), "Other note")
        val canvas = canvas()
        try {
            canvas.showNotes(listOf(note, other))
            assertEquals(2, canvas.tabs.tabCount)
            canvas.focusNote(other)
            assertEquals(1, canvas.tabs.selectedIndex)
            canvas.showNotes(listOf(note.copy(text = "Updated"), other))
            assertEquals(1, canvas.tabs.selectedIndex)
            canvas.focusNote(note)
            assertEquals(0, canvas.tabs.selectedIndex)
            canvas.showNotes(listOf(other))
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals("Note · b.kt", canvas.tabs.getTitleAt(0))
            assertTrue(descendants(canvas).none { it.name == "Resize block" })
        } finally { canvas.dispose() }
    }

    fun testWideTabsScrollHorizontallyAndRefreshPreservesReadingPosition() {
        val canvas = canvas().apply { setSize(300, 300) }
        val longNote = note.copy(text = "Long explanation. ".repeat(2000))
        try {
            canvas.showNotes(listOf(longNote))
            layoutTree(canvas)
            val page = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            assertTrue(page.horizontalScrollBar.maximum > page.horizontalScrollBar.visibleAmount)
            page.horizontalScrollBar.value = 60
            val position = Point(page.viewport.viewPosition)
            canvas.showNotes(listOf(longNote.copy(text = longNote.text + "Updated.")))
            layoutTree(canvas)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val refreshed = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            assertEquals(position, refreshed.viewport.viewPosition)
        } finally { canvas.dispose() }
    }

    fun testWheelScrollsLongNotesWithoutChangingFontSize() {
        val canvas = canvas()
        try {
            canvas.showNotes(listOf(note.copy(text = "Long explanation. ".repeat(2000))))
            layoutTree(canvas)
            val text = descendants(canvas).filterIsInstance<javax.swing.JTextArea>().single()
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
        val topics = ChangeTopics(listOf(file)).assign(file.path, "Product>Feature")
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, ChangeSummaries(buildSummaryTree(listOf(file))), canRun = false))
            layoutTree(canvas)
            fun document() = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            fun title() = descendants(document()).filterIsInstance<javax.swing.JTextArea>().first { it.text == "Feature" }
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

    fun testDocumentZoomReachesTenPercentWithButtonsAndWheel() {
        val file = ReviewFile("src/feature.kt", "/repo/src/feature.kt", null, "modified",
            "@@ -1 +1 @@\n-old\n+new\n", "1")
        val topics = ChangeTopics(listOf(file)).assign(file.path, "Product>Feature")
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, ChangeSummaries(buildSummaryTree(listOf(file))), canRun = false))
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
        val topics = ChangeTopics(files).assign(files[0].path, "Product>Feature>One")
            .assign(files[1].path, "Product>Feature>Two")
        val summaries = ChangeSummaries(buildSummaryTree(files))
        val canvas = canvas().apply { setSize(1600, 700); summariesView.mode = ChangeSummariesMode.DOCUMENT }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, summaries, canRun = false),
                summaries = SummariesCard(summaries, running = false, canRun = false))
            fun level(name: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == name }
            fun topicPanel() = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            fun folderPanel() = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
            layoutTree(canvas)
            val topicWidth = topicPanel().preferredSize.width
            val folderWidth = folderPanel().preferredSize.width
            level("level:0").doClick()
            level("summary-level:0").doClick()
            layoutTree(canvas)
            assertTrue(level("level:0").isSelected)
            assertTrue(level("summary-level:0").isSelected)
            assertEquals(setOf("topic:0:product", "topic:pending"), topicPanel().components.mapNotNull { it.name }.filter { it.startsWith("topic:") }.toSet())
            assertEquals(listOf("node:root"), folderPanel().components.mapNotNull { it.name }.filter { it.startsWith("node:") })
            assertTrue(topicPanel().preferredSize.width < topicWidth)
            assertTrue(folderPanel().preferredSize.width < folderWidth)
            for (panel in listOf(topicPanel(), folderPanel())) {
                assertEquals(files.size, descendants(panel).filterIsInstance<PatchView>().size)
            }
            level("level:3").doClick()
            level("summary-level:${ChangeSummariesViewState.branchLevels(summaries.tree)}").doClick()
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
        val topics = ChangeTopics(listOf(file)).assign(file.path, "Product>$label")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ChangeSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == file.path }.id to longWord))
        val canvas = canvas().apply { setSize(1200, 700) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, summaries, canRun = false))
            repeat(4) { (descendants(canvas).first { it.name == "document-zoom-out" } as javax.swing.JButton).doClick() }
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            val rail = panel.components.single { it.name == "topic:1:product > ${label.lowercase()}" }
            val summary = panel.components.single { it.name == "summary:src/feature.kt" }
            val title = descendants(rail as Container).filterIsInstance<javax.swing.JTextArea>().single { it.text == label }
            val summaryText = descendants(summary as Container).filterIsInstance<javax.swing.JTextArea>().single { it.text == longWord }
            assertTrue(rail.width > com.intellij.util.ui.JBUI.scale(DocumentColumnsPanel.RAIL_CARD_WIDTH))
            assertTrue(summary.width > com.intellij.util.ui.JBUI.scale(DocumentColumnsPanel.SUMMARY_WIDTH))
            assertTrue(title.getFontMetrics(title.font).stringWidth(label) <= title.width)
            assertTrue(summaryText.getFontMetrics(summaryText.font).stringWidth(longWord) <= summaryText.width)
            assertWordFitsOnOneLine(title, label)
            assertWordFitsOnOneLine(summaryText, longWord)
            assertTrue(rail.height >= (rail as javax.swing.JComponent).preferredSize.height)
            rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
            val selectedSummary = panel.components.single { it.name?.startsWith("card-summary:") == true } as Container
            assertWordFitsOnOneLine(descendants(selectedSummary).filterIsInstance<javax.swing.JTextArea>()
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
        val summaries = ChangeSummaries(tree).summarize(
            tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to sentence } + (folderNode.id to sentence))
        val canvas = canvas().apply { setSize(1200, 700); summariesView.mode = ChangeSummariesMode.DOCUMENT }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = false))
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
                assertWordFitsOnOneLine(descendants(rail()).filterIsInstance<javax.swing.JTextArea>().single(), "$folder/")
                assertWordFitsOnOneLine(descendants(summary()).filterIsInstance<javax.swing.JTextArea>().single(), word)
            }
            assertTrue(railWidth > initialRailWidth)
            assertTrue(summaryWidth > initialSummaryWidth)
            val rail = rail()
            rail.dispatchEvent(MouseEvent(rail, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
            val selectedSummary = document().components.single { it.name == "card-summary:${folderNode.identity}" } as Container
            assertWordFitsOnOneLine(descendants(selectedSummary).filterIsInstance<javax.swing.JTextArea>()
                .single { it.text == sentence }, word)
        } finally { canvas.dispose() }
    }

    fun testTopicsDocumentAlignsRailCardsAndSummariesWithFileDiffs() {
        val files = listOf("src/wallet.kt", "src/auth.kt", "ui/form.kt", "docs/new.md").mapIndexed { index, path ->
            ReviewFile(path, "/repo/$path", null, "modified",
                "@@ -1,2 +1,${2 + index * 6} @@\n ctx\n-old\n" + "+new\n".repeat(1 + index * 6), "1")
        }
        val topics = ChangeTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val tree = buildSummaryTree(files)
        val summaries = ChangeSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == "src/auth.kt" }.id to "Adds token refresh."))
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, summaries, canRun = true))
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
            val texts = descendants(panel).filterIsInstance<javax.swing.JTextArea>().map { it.text }
            assertTrue(texts.contains("Adds token refresh."))
            assertEquals(3, texts.count { it == "No summary yet" })
            assertEquals(listOf("hunk - - @@ -1,2 +1,2 @@", "context 1 1  ctx", "removed 2 - -old", "added - 2 +new"),
                descendants(sections[1] as Container).filterIsInstance<PatchView>().single().describe().take(4))

            // Selecting a topic outlines its files and survives summary updates.
            val auth = named("topic:2:storage > user > auth") as javax.swing.JComponent
            auth.dispatchEvent(MouseEvent(auth, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            assertTrue(canvas.topicsView.isSelected(panel.outline.nodes.single { it.identity == "storage > user > auth" }))
            // The selected topic's summary replaces its files' notes in the margin, level with its card.
            val authSummary = named("card-summary:2:storage > user > auth") as Container
            assertEquals(auth.y, authSummary.y)
            assertTrue(authSummary.x + authSummary.width <= sections[1].x)
            assertFalse(named("summary:src/auth.kt").isVisible)
            assertTrue(named("summary:src/wallet.kt").isVisible)
            assertTrue(descendants(authSummary).filterIsInstance<javax.swing.JTextArea>().any { it.text == "Adds token refresh." })
            // Hovering another card shows its summary instead; leaving it brings the selection's back.
            val storage = named("topic:0:storage") as javax.swing.JComponent
            storage.dispatchEvent(MouseEvent(storage, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false))
            val storageSummary = named("card-summary:0:storage") as Container
            assertEquals(storage.y, storageSummary.y)
            assertTrue(panel.components.none { it.name == "card-summary:2:storage > user > auth" })
            assertTrue(listOf("src/auth.kt", "src/wallet.kt").none { named("summary:$it").isVisible })
            assertTrue(descendants(storageSummary).filterIsInstance<javax.swing.JTextArea>().any { it.text == "No summary yet" })
            storage.dispatchEvent(MouseEvent(storage, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false))
            assertTrue(panel.components.none { it.name == "card-summary:0:storage" })
            assertTrue(named("summary:src/wallet.kt").isVisible)
            named("card-summary:2:storage > user > auth")
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(tree.nodes.single { it.path == "ui/form.kt" }.id to "Validates the card number."))
            canvas.updateTopics(TopicsCard(topics, more, canRun = false))
            assertEquals(bounds, canvas.components.single().bounds)
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.JTextArea>().any { it.text == "Validates the card number." })
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
        val topics = files.fold(ChangeTopics(files)) { result, file -> result.assign(file.path, "Storage>User") }
        val canvas = NoteCanvas({}, {}).apply { setSize(1100, 400) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, ChangeSummaries(buildSummaryTree(files)), canRun = false))
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
            canvas.showNotes(emptyList(), summaries = SummariesCard(ChangeSummaries(tree), running = false, canRun = false))
            canvas.summariesView.selectLevel(ChangeSummariesViewState.branchLevels(tree), tree)
            descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:hierarchy" }.doClick()
            layoutTree(canvas)
            val hierarchy = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
            val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, hierarchy) as javax.swing.JScrollPane
            val root = hierarchy.components.single { it.name == "node:${tree.root.identity}" } as Container
            val heading = root.components.first()
            val outer = canvas.tabs.selectedComponent as javax.swing.JScrollPane
            outer.verticalScrollBar.value = 120
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
        val topics = ChangeTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val tree = buildSummaryTree(files)
        val summaries = ChangeSummaries(tree).summarize(mapOf(tree.nodes.single { it.path == "src/auth.kt" }.id to "Adds token refresh."))
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
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, summaries, canRun = true))
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
            val texts = descendants(panel()).filterIsInstance<javax.swing.JTextArea>().map { it.text }
            assertTrue(texts.contains("Adds token refresh."))
            assertEquals(3, texts.count { it == "No summary yet" })

            // A folded topic takes one row and shows the summary of exactly its files, else their names.
            click(descendants(panel()).single { it.name == "toggle:2:storage > user > auth" })
            assertFalse("file:src/auth.kt" in names())
            assertEquals("Adds token refresh.", (named("summary:2:storage > user > auth") as javax.swing.JTextArea).text)
            click(descendants(panel()).single { it.name == "toggle:1:storage > user" })
            assertEquals("auth.kt, wallet.kt", (named("summary:1:storage > user") as javax.swing.JTextArea).text)
            assertEquals(named("topic:1:storage > user").height, named("topic:0:storage").height)

            // Level 1 folds every top-level topic; Files shows every level again.
            fun level(index: Int) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "level:$index" }
            assertEquals(listOf("1", "2", "3", "Files"), (0..3).map { level(it).text })
            level(0).doClick(); layoutTree(canvas)
            assertEquals(listOf("topic:0:storage", "topic:0:ui"), names().filter { it.startsWith("topic:") && it != "topic:pending" }.sorted())
            assertTrue(level(0).isSelected)
            level(3).doClick(); layoutTree(canvas)
            assertTrue(names().containsAll(files.map { "file:${it.path}" }))

            // Selecting a topic outlines it and its files, and survives summary updates.
            click(named("topic:2:storage > user > auth"))
            assertTrue(canvas.topicsView.isSelected(panel().outline.nodes.single { it.identity == "storage > user > auth" }))
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(tree.nodes.single { it.path == "ui/form.kt" }.id to "Validates the card number."))
            canvas.updateTopics(TopicsCard(topics, more, canRun = false))
            assertEquals(bounds, canvas.components.single().bounds)
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.JTextArea>().any { it.text == "Validates the card number." })
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
        val summaries = ChangeSummaries(tree).summarize(written.associate { id(it) to "Summary of $it." })
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun names() = panel().components.mapNotNull { it.name }
        fun named(name: String) = panel().components.single { it.name == name }
        fun note(identity: String) = (named("summary:$identity") as javax.swing.JTextArea).text
        fun bottom(component: Component) = component.y + component.height
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        fun toggleButton(text: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:${text.lowercase()}" }
        fun level(index: Int) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summary-level:$index" }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true))
            layoutTree(canvas)
            // The outline stays the default; the hierarchy is a second mode of the same card.
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeSummariesPanel>().size)
            toggleButton("Hierarchy").doClick(); layoutTree(canvas)
            assertTrue(descendants(canvas).none { it is ChangeSummariesPanel })
            assertEquals(ChangeSummariesMode.HIERARCHY, canvas.summariesView.mode)

            // The root folder in the first column, then its folders; each file is a leaf one column right of its folder.
            val root = named("node:root")
            val src = named("node:directory:src")
            val wallet = named("node:directory:src/wallet")
            val ledger = named("file:src/wallet/Ledger.kt")
            val walletFile = named("file:src/wallet/Wallet.kt")
            val auth = named("file:src/auth/Auth.kt")
            val readme = named("file:docs/README.md")
            assertTrue(root.x < src.x && src.x < wallet.x && wallet.x < ledger.x)
            assertEquals(wallet.x, auth.x)
            assertEquals(src.x, readme.x)
            // Every folder spans exactly the rows beneath it.
            assertEquals(ledger.y, root.y)
            assertEquals(bottom(readme), bottom(root))
            assertEquals(ledger.y, src.y)
            assertEquals(bottom(auth), bottom(src))
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
            assertEquals("No summary yet · wallet/, auth/Auth.kt", note("directory:src"))
            assertEquals(named("node:directory:src").y, named("summary:directory:src").y)

            // The switcher follows folding by hand; level 1 folds the root, and Files shows every folder again.
            assertEquals(listOf("1", "2", "3", "Files"), (0..3).map { level(it).text })
            assertEquals(listOf(1), (0..3).filter { level(it).isSelected })
            level(0).doClick(); layoutTree(canvas)
            assertEquals(listOf("node:root", "summary:root"), names().sorted())
            assertEquals("No summary yet · src/, docs/README.md", note("root"))
            assertTrue(level(0).isSelected)
            level(3).doClick(); layoutTree(canvas)
            assertTrue(names().containsAll(files.map { "file:${it.path}" }))
            assertTrue(level(3).isSelected)

            // Selecting a folder outlines it and survives summary updates.
            click(named("node:directory:src"))
            assertEquals("directory:src", canvas.summariesView.selected)
            val bounds = canvas.components.single().bounds
            val more = summaries.summarize(mapOf(id("directory:src") to "Adds wallets and auth."))
            canvas.updateSummaries(SummariesCard(more, running = false, canRun = true))
            assertEquals(bounds, canvas.components.single().bounds)
            assertEquals("directory:src", canvas.summariesView.selected)
            descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "auth/Auth.kt" }.doClick()
            assertEquals(listOf("src/auth/Auth.kt"), opened)
            // Back in the outline, the selection carries over with its details.
            toggleButton("Outline").doClick(); layoutTree(canvas)
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeSummariesPanel>().size)
            assertTrue(descendants(canvas).any { it.name == "details:directory:src" })
        } finally { canvas.dispose() }
    }

    fun testSummariesDocumentAlignsFolderRailAndSummariesWithFileDiffs() {
        val files = listOf("src/wallet/Wallet.kt", "src/wallet/Ledger.kt", "src/auth/Auth.kt", "docs/README.md").mapIndexed { index, path ->
            ReviewFile(path, "/repo/$path", null, "modified",
                "@@ -1,2 +1,${2 + index * 6} @@\n ctx\n-old\n" + "+new\n".repeat(1 + index * 6), "1")
        }
        val tree = buildSummaryTree(files)
        fun id(identity: String) = tree.nodes.single { it.identity == identity }.id
        val summaries = ChangeSummaries(tree).summarize(mapOf(id("file:src/auth/Auth.kt") to "Adds token refresh."))
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900) }
        fun toggleButton(text: String) = descendants(canvas).filterIsInstance<javax.swing.JToggleButton>().single { it.name == "summaries-mode:${text.lowercase()}" }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true))
            layoutTree(canvas)
            toggleButton("Document").doClick(); layoutTree(canvas)
            assertEquals(ChangeSummariesMode.DOCUMENT, canvas.summariesView.mode)
            assertTrue(descendants(canvas).none { it is ChangeSummariesPanel || it is ChangeSummaryHierarchyPanel })
            val panel = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
            fun named(name: String) = panel.components.single { it.name == name }
            // Tree order: subfolders before files, then alphabetical.
            assertEquals(listOf("src/wallet/Ledger.kt", "src/wallet/Wallet.kt", "src/auth/Auth.kt", "docs/README.md"), panel.files.map { it.path })
            val sections = panel.files.map { named("file:${it.path}") }
            val root = named("node:root")
            val src = named("node:directory:src")
            val wallet = named("node:directory:src/wallet")
            // Each folder card spans the diffs of its files, with the root outermost and deepest folder next to the diffs.
            assertEquals(sections[0].y, root.y)
            assertEquals(sections[3].y + sections[3].height, root.y + root.height)
            assertEquals(sections[0].y, src.y)
            assertEquals(sections[2].y + sections[2].height, src.y + src.height)
            assertEquals(sections[0].y, wallet.y)
            assertEquals(sections[1].y + sections[1].height, wallet.y + wallet.height)
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
            val texts = descendants(panel).filterIsInstance<javax.swing.JTextArea>().map { it.text }
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
            assertTrue(panel.files.take(3).none { named("summary:${it.path}").isVisible })
            assertTrue(named("summary:docs/README.md").isVisible)
            val more = summaries.summarize(mapOf(id("file:docs/README.md") to "Documents the setup."))
            canvas.updateSummaries(SummariesCard(more, running = false, canRun = false))
            assertTrue(descendants(canvas).filterIsInstance<javax.swing.JTextArea>().any { it.text == "Documents the setup." })
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
        val canvas = NoteCanvas({ opened += it }, {}, showDiffCard = { requested += it }, project = project)
            .apply { setSize(1400, 900) }
        try {
            val sources = paths.map { DiffSourceBlock(DiffSourceRange(it, 1, 1), "val a = 1") }
            canvas.showNotes(paths.map { DiffNote(listOf(it), "Explanation.") }, sources)
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
            canvas.showNotes(emptyList(), diffs = listOf(file))
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
        val source = DiffSourceBlock(DiffSourceRange("src/a.kt", 8, 9), "val x = 1\n  println(x)")
        val canvas = NoteCanvas({}, {}, project = project).apply { setSize(1000, 700) }
        try {
            canvas.showNotes(listOf(note), listOf(source))
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
            canvas.showNotes(listOf(note), listOf(source, source.copy(range = source.range.copy(side = "before"))))
            val other = descendants(canvas).filterIsInstance<DiffSourceEditor>().single { it.editor !== viewer }.editor
            canvas.showNotes(listOf(note), listOf(source.copy(range = source.range.copy(side = "before"))))
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
        val canvas = NoteCanvas({}, {}, showDiffCard = { requested += it }, removeDiff = { removed += it }, project = project)
            .apply { setSize(1400, 900) }
        try {
            val source = DiffSourceBlock(DiffSourceRange("src/b.kt", 1, 1), "val b = 3")
            canvas.showNotes(listOf(note), listOf(source))
            val diffButtons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                .filter { it.toolTipText == "Show diff in tab" }
            assertEquals(2, diffButtons.size)
            diffButtons.forEach { it.doClick() }
            assertEquals(listOf("src/a.kt", "src/b.kt"), requested)

            canvas.showNotes(listOf(note), listOf(source), listOf(fileA, fileB))
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

            canvas.showNotes(listOf(note), listOf(source), listOf(fileB))
            assertTrue(viewerA.isDisposed && viewerA.editors.all { it.isDisposed })
            assertFalse(viewerB.isDisposed)
            canvas.dispose()
            assertTrue(viewerB.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testSymbolNotesHaveSeparateTabsAndDiagramBounds() {
        val symbol = DiffSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        val evidence = DiffSymbolEvidence(symbol, listOf(DiffSymbolLinks("Calls", (1..3).map {
            symbol.copy(name = "callee$it", qualifiedName = "Store.callee$it", offset = 10 + it)
        }, 20)))
        val semantic = note.copy(symbol = symbol.selector, evidence = evidence)
        val canvas = canvas()
        canvas.showNotes(listOf(note, semantic))
        assertEquals(2, canvas.tabs.tabCount)
        canvas.focusNote(semantic)
        val title = canvas.tabs.getTitleAt(canvas.tabs.selectedIndex)
        val diagram = descendants(canvas).filterIsInstance<DiffSymbolDiagram>().single()
        assertEquals(5, diagram.componentCount) // Root, three callees, one omitted count.
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "+17 calls not shown" })
        canvas.showNotes(listOf(note, semantic.copy(text = "Updated")))
        assertEquals(title, canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
        canvas.showNotes(listOf(semantic))
        assertEquals(title, canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
    }

    fun testDiagramBoundsMixedRelationshipsAndDistinguishesOwners() {
        val symbol = DiffSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        fun neighbors(owner: String) = (1..3).map {
            symbol.copy(qualifiedName = "$owner$it.save", offset = 10 + it)
        }
        val diagram = DiffSymbolDiagram(DiffSymbolEvidence(symbol, listOf(
            DiffSymbolLinks("Callers in this file", neighbors("Caller"), 5, incoming = true),
            DiffSymbolLinks("Calls", neighbors("Target"), 4),
            DiffSymbolLinks("References", neighbors("Other"), 3),
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
        val base = DiffSymbol("src/a.kt", "/src/a.kt", "a", "A.a", dev.insight.callgraph.SymbolKind.FUNCTION, 1, 0)
        fun step(name: String, vararg calls: String) = DiffNote(listOf("src/a.kt"), "$name does work. More detail.", "s:$name",
            DiffSymbolEvidence(base.copy(name = name, qualifiedName = "A.$name", offset = name.hashCode()), emptyList(),
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
        canvas.showNotes(notes)
        assertEquals(5, canvas.tabs.tabCount) // Flow card plus four notes.
        val diagram = descendants(canvas).filterIsInstance<ChangeFlowDiagram>().single()
        val links = descendants(diagram).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals(listOf("A.a", "A.b", "A.c", "↺ A.a", "↺ A.c"), links.map { it.text })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "b does work" })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "1 other symbol notes have no calls to or from these" })
        diagram.setSize(diagram.preferredSize)
        assertTrue(diagram.components.all { diagram.bounds.contains(it.bounds) })

        links[2].doClick()
        assertTrue(descendants(canvas.tabs.selectedComponent as Container).filterIsInstance<javax.swing.JTextArea>().any { it.text.startsWith("c ") })
        canvas.showNotes(notes.take(3))
        assertEquals(4, canvas.tabs.tabCount)
        canvas.showNotes(listOf(notes[3]))
        assertEquals(1, canvas.tabs.tabCount) // The flow disappears once no calls connect notes.
    }

    fun testSummariesPrescoreAllNonLeafWordsAndSearchOnlyOnClick() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/hierarchy/Hierarchy.kt", "ui/summaries/Summaries.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Deleted views: canvas, hierarchy, summaries."
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { node ->
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
            override fun prescore(content: ChangeSummaries, words: List<Pair<Int, Int>>,
                                  scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job {
                traced += words
                saveContext = scored
                finishScoring = done
                for ((id, word) in words) {
                    val text = content.text(id)!!
                    val split = SummaryWord.split(text)
                    val context = (listOf(split[0], split[1], split[word])).distinctBy { it.index }.sortedBy { it.index }
                    scored(SummaryContext(content.tree[id].identity, text, split[word], context, SummaryTracer.query(text, context)))
                }
                return Job().also { scoreJobs += it }
            }

            override fun search(content: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job {
                searched += context
                finishSearch = done
                return Job().also { searchJobs += it }
            }
        }
        val canvas = NoteCanvas({}, {}, traceSummaries = launcher).apply { setSize(1600, 900) }
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun rootText() = descendants(named("summary:root").single() as Container).filterIsInstance<javax.swing.JTextArea>().first()
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
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true))
            layoutTree(canvas)
            assertTrue(named("summaries-interactive").isEmpty())
            assertTrue(traced.isEmpty())
            clickWord("hierarchy")
            assertNull("Unscored words must not trigger scoring on demand", canvas.summariesView.tracing)
            canvas.summariesView.selected = null
            (named("summaries-score").single() as javax.swing.JButton).doClick()
            val expected = tree.nodes.filter { it.children.isNotEmpty() }.flatMap { node ->
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

            hoverWord("hierarchy")
            assertEquals(scoredCount, traced.size)
            assertTrue("Scoring alone must not search candidates", searched.isEmpty())
            assertNull(canvas.summariesView.selected)
            assertEquals(3, rootText().highlighter.highlights.size)
            assertTrue((named("trace:root").single() as javax.swing.JTextArea).text.contains("Click a highlighted word"))

            // Clicking a context word searches the original query, without rescoring that word.
            clickWord("Deleted")
            assertEquals(scoredCount, traced.size)
            assertEquals("hierarchy", searched.single().word.text)
            assertTrue((named("trace:root").single() as javax.swing.JTextArea).text.contains("Searching"))
            clickWord("views")
            assertEquals("Repeated clicks must not start overlapping searches", 1, searched.size)
            val context = searched.single()
            val result = SummaryTrace("root", root, context.word, context.context, context.query,
                listOf(SummaryMatch(hierarchy.id, hierarchy.identity, 0.9, 1), SummaryMatch(canvasFile.id, canvasFile.identity, 0.1, 1)), 3)
            finishSearch!!(Result.success(result))
            layoutTree(canvas)
            // The best related summary is selected, labelled and its level opened; unrelated ones are not marked.
            assertEquals(hierarchy.identity, canvas.summariesView.selected)
            assertTrue((named("match:${hierarchy.identity}").single() as javax.swing.JTextArea).text.startsWith("● Best match for “Deleted views: hierarchy”"))
            assertTrue(named("match:${canvasFile.identity}").isEmpty())
            assertTrue((named("trace:root").single() as javax.swing.JTextArea).text.contains("1 of 3 summaries"))
            // The traced word and its two context words are highlighted, not the siblings.
            assertEquals(3, rootText().highlighter.highlights.size)

            clickWord("hierarchy")
            assertEquals("Hover and clicks never request scoring", scoredCount, traced.size)
            assertEquals("Completed searches are reused", 1, searched.size)

            // Moving onto another cached word cancels pending searches and ignores their late results.
            clickWord("canvas")
            clickWord("canvas")
            assertEquals(2, searched.size)
            clickWord("hierarchy")
            assertTrue(searchJobs.last().isCancelled)
            finishSearch!!(Result.success(result))
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
            finishScoring!!(Result.success(Unit))
            assertTrue("Cancelled scoring must ignore late results", canvas.summariesView.scored.values.none { it.identity == "root" })
        } finally {
            canvas.dispose()
        }
    }

    /** Scores every word with the summary's first two words as context; searches wait for the test to finish them. */
    private class FakeTraceLauncher : SummaryTraceLauncher {
        val scoredWords = mutableListOf<Pair<Int, Int>>()
        val searched = mutableListOf<SummaryContext>()
        var finishSearch: ((Result<SummaryTrace>) -> Unit)? = null

        override fun prescore(content: ChangeSummaries, words: List<Pair<Int, Int>>,
                              scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job {
            scoredWords += words
            for ((id, word) in words) {
                val text = content.text(id)!!
                val split = SummaryWord.split(text)
                val context = listOf(split[0], split[1], split[word]).distinctBy { it.index }.sortedBy { it.index }
                scored(SummaryContext(content.tree[id].identity, text, split[word], context, SummaryTracer.query(text, context)))
            }
            done(Result.success(Unit))
            return Job()
        }

        override fun search(content: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job {
            searched += context
            finishSearch = done
            return Job()
        }

        fun finish(context: SummaryContext, candidates: Int, vararg matches: SummaryMatch) = finishSearch!!(Result.success(
            SummaryTrace(context.identity, context.summary, context.word, context.context, context.query, matches.sortedByDescending { it.probability }, candidates)))
    }

    private fun pointAtWord(canvas: Container, area: javax.swing.JTextArea, word: String, event: Int) {
        val box = area.modelToView2D(area.text.indexOf(word) + 1)
        area.dispatchEvent(MouseEvent(area, event, 0, 0, box.centerX.toInt(), box.centerY.toInt(), 1, false,
            if (event == MouseEvent.MOUSE_CLICKED) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON))
        layoutTree(canvas)
    }

    fun testSummariesHierarchyTracesFoldedSummariesAndOpensThePathToTwoLevelsBelow() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/canvas/Paint.kt", "ui/hierarchy/Hierarchy.kt", "ui/hierarchy/Columns.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "deleted", "@@ -1 +0,0 @@\n-old\n", "1")
        }
        val tree = buildSummaryTree(files)
        val root = "Deleted views: canvas, hierarchy."
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.ROOT) root else "Deletes ${node.label}."
        })
        fun node(path: String) = tree.nodes.single { it.path == path && it.kind != SummaryKind.ROOT }
        val canvasDir = node("ui/canvas")
        val hierarchyDir = node("ui/hierarchy")
        val hierarchyFile = node("ui/hierarchy/Hierarchy.kt")
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, traceSummaries = launcher).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun note(identity: String) = panel().components.single { it.name == "summary:$identity" } as javax.swing.JTextArea
        fun badges(identity: String) = descendants(panel().anchors.getValue(identity) as Container)
            .filterIsInstance<javax.swing.JTextArea>().filter { it.name == "match" }.map { it.text }
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true))
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
            pointAtWord(canvas, note("root"), "hierarchy", MouseEvent.MOUSE_MOVED)
            assertEquals("hierarchy", canvas.summariesView.tracing!!.word.text)
            assertEquals(3, note("root").highlighter.highlights.size)
            assertTrue((named("trace-status").single() as javax.swing.JTextArea).text.contains("Click a highlighted word"))
            pointAtWord(canvas, note("root"), "hierarchy", MouseEvent.MOUSE_CLICKED)
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
            assertTrue((named("trace-status").single() as javax.swing.JTextArea).text.contains("3 of 6 summaries"))

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

    fun testTopicsHierarchyScoresAndTracesFoldedTopicSummaries() {
        val files = listOf("src/wallet.kt", "src/auth.kt", "ui/form.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val topics = ChangeTopics(files)
            .assign("src/wallet.kt", "Storage>User>Wallet")
            .assign("src/auth.kt", "Storage>User>Auth")
            .assign("ui/form.kt", "UI>Checkout")
        val topicTree = buildTopicSummaryTree(topics)
        val topicSummaries = ChangeSummaries(topicTree).summarize(topicTree.nodes.associate { node ->
            node.id to if (node.identity == "topic:storage") "Adds user wallets and token auth." else "Changes ${node.label}."
        })
        val shared = ChangeSummaries(buildSummaryTree(files))
        val launcher = FakeTraceLauncher()
        val canvas = NoteCanvas({}, {}, traceSummaries = launcher).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().single()
        fun named(name: String) = descendants(canvas).filter { it.name == name }
        fun note(name: String) = panel().components.single { it.name == "summary:$name" } as javax.swing.JTextArea
        fun badges(identity: String) = descendants(panel().anchors.getValue(identity) as Container)
            .filterIsInstance<javax.swing.JTextArea>().filter { it.name == "match" }.map { it.text }
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            val card = TopicsCard(topics, shared, canRun = true, topicSummaries)
            canvas.showNotes(emptyList(), topics = card)
            layoutTree(canvas)
            assertTrue("Scoring belongs to the hierarchy", named("topics-score").isEmpty())
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
            pointAtWord(canvas, note("0:storage"), "auth", MouseEvent.MOUSE_CLICKED)
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
            assertTrue((named("trace-status").single() as javax.swing.JTextArea).text.contains("1 of 3 summaries"))

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

    fun testSummariesShowNamesAboveSummaries() {
        val files = listOf("ui/canvas/Canvas.kt", "ui/hierarchy/Hierarchy.kt").map { path ->
            ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { it.id to "Summary of ${it.identity}." })
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900) }
        fun texts(identity: String) = descendants(descendants(canvas).single { it.name == "summary:$identity" } as Container)
            .filterIsInstance<javax.swing.JTextArea>().map { it.text }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = true))
            layoutTree(canvas)
            assertEquals(listOf("Summary of file:ui/canvas/Canvas.kt."), texts("file:ui/canvas/Canvas.kt"))
            (descendants(canvas).single { it.name == "summaries-show-names" } as javax.swing.JCheckBox).doClick()
            layoutTree(canvas)
            assertTrue(canvas.summariesView.showNames)
            assertEquals(listOf("ui/", "Summary of root."), texts("root"))
            assertEquals(listOf("canvas/Canvas.kt", "Summary of file:ui/canvas/Canvas.kt."), texts("file:ui/canvas/Canvas.kt"))
            canvas.updateSummaries(SummariesCard(summaries, running = true, canRun = false))
            layoutTree(canvas)
            assertTrue("Show names survives re-rendering", (descendants(canvas).single { it.name == "summaries-show-names" } as javax.swing.JCheckBox).isSelected)
        } finally {
            canvas.dispose()
        }
    }

    private fun canvas() =NoteCanvas({}, {}).apply { setSize(1000, 700); showNotes(listOf(note)) }
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    /** Check the laid out Swing text, including the wrapping performed when it is painted. */
    private fun assertWordFitsOnOneLine(text: javax.swing.JTextArea, word: String) {
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
