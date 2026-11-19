package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import dev.insight.codereview.CodeFile
import dev.insight.context.ContextData
import dev.insight.context.ContextScopeKind
import dev.insight.context.ContextSummaryActions
import dev.insight.context.defaultScopeIndex
import dev.insight.diff.DiffFileEditor
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewSummaries
import dev.insight.review.RadarSeries
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JToggleButton
import javax.swing.text.StyleConstants

class LineChartViewTest : BasePlatformTestCase() {
    fun testDirectoryPickerKeepsTheDirectoryScopeWhenOnlyOneFileIsInReview() {
        val path = "src/deep/file.kt"
        val files = listOf(CodeFile(path, "/repo/$path", "val captured = 1"), CodeFile("other.kt", "/repo/other.kt", "outside"))
        val node = buildSummaryTree(files).nodes.single { it.path == "src/deep" }
        val scopes = summaryPickerScopes(project, node, files.filter { it.path in node.files }, ContextData())

        assertEquals(listOf(ContextScopeKind.DIRECTORY, ContextScopeKind.FILE), scopes.map { it.kind })
        val directory = scopes[defaultScopeIndex(scopes)]
        assertEquals("src/deep", directory.path)
        assertEquals(listOf(path), directory.files)
        assertTrue(directory.text.contains("val captured = 1"))
        assertFalse(directory.text.contains("outside"))
    }

    fun testCodeAndDiffRowsCountsAndBreadcrumbsOfferContextPicker() {
        for (vocabulary in listOf(ReviewVocabulary.CODE, ReviewVocabulary.DIFF)) {
            val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { path ->
                if (vocabulary == ReviewVocabulary.CODE) CodeFile(path, "/repo/$path", "captured source")
                else dev.insight.diff.ReviewFile(path, "/repo/$path", null, "modified",
                    "@@ -1 +1 @@\n-old\n+new", path)
            }
            val canvas = NoteCanvas({}, {}, project = project, vocabulary = vocabulary)
            fun component(name: String) = descendants(canvas).filterIsInstance<JComponent>().single { it.name == name }
            fun assertContextMenu(component: JComponent) {
                val menu = component.componentPopupMenu
                assertNotNull(menu)
                assertEquals(listOf("Context…"), menu.components.filterIsInstance<JMenuItem>().map { it.text })
            }
            try {
                canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
                assertContextMenu(component("charts-lines-entry:readme.md"))
                assertContextMenu(component("charts-lines-count:readme.md"))
                assertContextMenu(component("charts-lines-entry:src"))
                assertContextMenu(component("charts-lines-crumb:"))
                (component("charts-lines-entry:src") as ActionLink).doClick()
                assertContextMenu(component("charts-lines-entry:src/a.kt"))
                assertContextMenu(component("charts-lines-crumb:"))
                assertContextMenu(component("charts-lines-crumb:src"))
            } finally { canvas.dispose() }
        }
    }

    fun testDrillDownBreadcrumbsFileOpeningAndChartSwitchRetainDirectory() {
        val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { CodeFile(it, "/repo/$it", "one\ntwo\n") }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun entries() = components().filterIsInstance<ActionLink>().filter { it.name?.startsWith("charts-lines-entry:") == true }
        fun click(name: String) = components().filterIsInstance<ActionLink>().single { it.name == name }.doClick()
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            assertEquals(listOf("charts-lines-entry:readme.md", "charts-lines-entry:src"), entries().map { it.name })
            click("charts-lines-entry:src")
            assertEquals(listOf("charts-lines-entry:src/a.kt", "charts-lines-entry:src/nested"), entries().map { it.name })
            assertEquals("4 lines · 2 files", components().filterIsInstance<JLabel>().single { it.name == "charts-lines-total" }.text)
            click("charts-lines-entry:src/nested")
            click("charts-lines-entry:src/nested/b.kt")
            assertEquals(listOf("src/nested/b.kt"), opened)
            selector().selectedItem = "Radar"
            assertEquals(1, components().filterIsInstance<RadarChartPanel>().size)
            selector().selectedItem = "File metrics"
            assertEquals("src/nested", components().filterIsInstance<LineChartPanel>().single().directory.path)
            click("charts-lines-crumb:src")
            val panel = components().filterIsInstance<LineChartPanel>().single().apply { setSize(900, 400); doLayout() }
            val image = BufferedImage(900, 400, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { panel.paint(it) }.dispose()
            click("charts-lines-crumb:")
            assertEquals(2, entries().size)
        } finally { canvas.dispose() }
    }

    fun testCodeMetricTabsShowLineByteAndFileCounts() {
        val files = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "é\n"),
            CodeFile("src/b.kt", "/repo/src/b.kt", "😀"),
            CodeFile("readme.md", "/repo/readme.md", ""),
        )
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE)
        fun components() = descendants(canvas)
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun total() = components().filterIsInstance<JLabel>().single { it.name == "charts-lines-total" }.text
        val path = "src/a.kt"
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            assertEquals(listOf("Lines of code", "Size", "Items"), (0 until tabs().tabCount).map { tabs().getTitleAt(it) })
            components().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src" }.doClick()
            components().filterIsInstance<JToggleButton>().single { it.name == "charts-lines-toggle:$path" }.doClick()
            assertEquals("2 lines · 2 files", total())
            tabs().selectedIndex = 1
            assertEquals("7 bytes · 2 files", total())
            tabs().selectedIndex = 2
            assertEquals("2 items", total())
            assertEquals("src", components().filterIsInstance<LineChartPanel>().single().directory.path)
            assertEquals(LineChartPreview(path, LineChartPreviewKind.SOURCE), canvas.chartsView.linesPreview)
        } finally { canvas.dispose() }
    }

    fun testDiffMetricSwitcherUpdatesCountsColorsAndRetainsNavigation() {
        val files = listOf("src/a.kt", "readme.md").map {
            dev.insight.diff.ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1,2 @@\n-old\n+new\n+extra", it)
        }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.DIFF)
        fun components() = descendants(canvas)
        fun selector(name: String) = components().filterIsInstance<JComboBox<*>>().single { it.name == name }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun total() = components().filterIsInstance<JLabel>().single { it.name == "charts-lines-total" }
        fun assertColor(series: RadarSeries) {
            assertEquals(RadarChartPanel.color(series), total().foreground)
            assertTrue(components().filter { it.name?.startsWith("charts-lines-count:") == true }.all {
                it.foreground == RadarChartPanel.color(series)
            })
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            assertEquals(listOf("Added lines", "Removed lines"), (0 until tabs().tabCount).map { tabs().getTitleAt(it) })
            assertEquals(RadarChartPanel.color(RadarSeries.ADDED), tabs().getTabComponentAt(0).foreground)
            assertEquals(RadarChartPanel.color(RadarSeries.REMOVED), tabs().getTabComponentAt(1).foreground)
            assertEquals("4 added lines · 2 files", total().text)
            assertColor(RadarSeries.ADDED)
            components().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src" }.doClick()
            tabs().selectedIndex = 1
            assertEquals("1 removed line · 1 file", total().text)
            assertColor(RadarSeries.REMOVED)
            components().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src/a.kt" }.doClick()
            assertEquals(listOf("src/a.kt"), opened)
            selector("charts-type-selector").selectedItem = "Radar"
            selector("charts-type-selector").selectedItem = "Changed lines"
            assertEquals("src", components().filterIsInstance<LineChartPanel>().single().directory.path)
            assertEquals(1, tabs().selectedIndex)
            assertColor(RadarSeries.REMOVED)
        } finally { canvas.dispose() }
    }

    fun testToggleShowsAndHidesFileAndDirectoryPreviewWithVisualHighlightAndDisposal() {
        for (vocabulary in listOf(ReviewVocabulary.CODE, ReviewVocabulary.DIFF)) {
            val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { path ->
                if (vocabulary == ReviewVocabulary.CODE) CodeFile(path, "/repo/$path", "val file = \"$path\"\n")
                else dev.insight.diff.ReviewFile(path, "/repo/$path", null, "modified",
                    "@@ -1 +1 @@\n-old\n+new\n", path)
            }
            val expectedShow = if (vocabulary == ReviewVocabulary.CODE) "Show source" else "Show diff"
            val expectedHide = if (vocabulary == ReviewVocabulary.CODE) "Hide source" else "Hide diff"
            val canvas = NoteCanvas({}, {}, project = project, vocabulary = vocabulary).apply { setSize(1600, 900) }
            fun all() = descendants(canvas)
            fun previewPanel() = all().filterIsInstance<HierarchyPreviewPanel>().single()
            fun toggle(path: String) = all().filterIsInstance<JToggleButton>().single { it.name == "charts-lines-toggle:$path" }
            fun row(path: String) = all().filterIsInstance<JComponent>().single { it.name == "charts-lines-row:$path" }
            fun previewPaths() = descendants(previewPanel()).mapNotNull { it.name?.takeIf { n -> n.startsWith("hierarchy-preview:") } }
                .map { it.removePrefix("hierarchy-preview:") }
            fun viewers() = descendants(previewPanel()).filterIsInstance<Disposable>().filter {
                it is DiffFileEditor || it is DiffSourceEditor
            }
            fun isDisposed(viewer: Disposable) =
                if (viewer is DiffFileEditor) viewer.isDisposed else (viewer as DiffSourceEditor).editor.isDisposed
            try {
                canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
                assertTrue(previewPaths().isEmpty())
                assertEquals(expectedShow, toggle("readme.md").toolTipText.orEmpty().substringBefore(" of "))
                assertFalse(toggle("readme.md").isSelected)
                assertFalse(row("readme.md").isOpaque)
                assertFalse(row("src").isOpaque)

                toggle("readme.md").doClick()
                assertTrue(toggle("readme.md").isSelected)
                assertEquals(expectedHide, toggle("readme.md").toolTipText.orEmpty().substringBefore(" of "))
                assertTrue(row("readme.md").isOpaque)
                assertFalse(row("src").isOpaque)
                assertEquals(listOf("readme.md"), previewPaths())
                val firstViewer = viewers().single()
                assertFalse(isDisposed(firstViewer))

                toggle("src").doClick()
                assertTrue(isDisposed(firstViewer))
                assertFalse(toggle("readme.md").isSelected)
                assertEquals(expectedShow, toggle("readme.md").toolTipText.orEmpty().substringBefore(" of "))
                assertFalse(row("readme.md").isOpaque)
                assertTrue(toggle("src").isSelected)
                assertEquals(expectedHide, toggle("src").toolTipText.orEmpty().substringBefore(" of "))
                assertTrue(row("src").isOpaque)
                assertEquals(listOf("src/nested/b.kt", "src/a.kt"), previewPaths())
                val dirViewers = viewers()
                assertEquals(2, dirViewers.size)

                // Drilling into the active directory highlights its breadcrumb while retaining its preview.
                all().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src" }.doClick()
                val srcCrumb = all().filterIsInstance<JComponent>().single { it.name == "charts-lines-crumb:src" }
                assertTrue(srcCrumb.font.isBold)
                assertNotNull(srcCrumb.border)
                assertEquals(listOf("src/nested/b.kt", "src/a.kt"), previewPaths())

                all().filterIsInstance<ActionLink>().single { it.name == "charts-lines-crumb:" }.doClick()
                val activeDirViewers = viewers()
                assertEquals(2, activeDirViewers.size)
                toggle("src").doClick()
                assertTrue(activeDirViewers.all(::isDisposed))
                assertFalse(toggle("src").isSelected)
                assertEquals(expectedShow, toggle("src").toolTipText.orEmpty().substringBefore(" of "))
                assertFalse(row("src").isOpaque)
                assertTrue(previewPaths().isEmpty())
            } finally { canvas.dispose() }
        }
    }

    fun testSummaryToggleSwitchesTheRightColumnAndShowsNewlyAvailableSummaries() {
        for (vocabulary in listOf(ReviewVocabulary.CODE, ReviewVocabulary.DIFF)) {
            val path = "src/a.kt"
            val files = listOf(path, "src/b.kt", "readme.md").map {
                if (vocabulary == ReviewVocabulary.CODE) CodeFile(it, "/repo/$it", "val captured = 1")
                else dev.insight.diff.ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1 @@\n-old\n+captured", it)
            }
            val tree = buildSummaryTree(files)
            val directory = tree.nodes.single { it.path == "src" }
            val file = tree.nodes.single { it.path == path }
            val requested = mutableListOf<Pair<String, SummaryKind>>()
            val actions = ContextSummaryActions({ _, _ -> true }, { target, kind -> requested += target to kind; true })
            val canvas = NoteCanvas({}, {}, project = project, vocabulary = vocabulary,
                summaryActions = SummaryCanvasActions(contextSummaryActions = actions))
            fun toggle(name: String) = descendants(canvas).filterIsInstance<JToggleButton>().single { it.name == name }
            fun summary() = descendants(canvas).filterIsInstance<WrappedSummaryText>().singleOrNull { it.name == "charts-lines-summary-text" }
            fun generate() = descendants(canvas).filterIsInstance<JButton>().singleOrNull { it.name == "charts-lines-generate-summary" }
            fun tagsCloud() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "charts-lines-tags-cloud" }
            fun firstWordSize(): Int = summary()!!.styledDocument.let { doc ->
                StyleConstants.getFontSize(doc.getCharacterElement(0).attributes)
            }
            try {
                val empty = ChartsCard(ReviewSummaries(tree))
                canvas.showNotes(CanvasState(emptyList(), charts = empty))
                toggle("charts-lines-summary-toggle:src").doClick()
                assertEquals("No summary available yet.", summary()?.text)
                assertTrue(toggle("charts-lines-summary-toggle:src").isSelected)
                generate()!!.doClick()
                assertEquals(listOf("src" to SummaryKind.DIRECTORY), requested)
                canvas.updateCharts(empty.copy(pendingEntries = setOf(directory.id)))
                assertEquals("Summarizing…", generate()?.text)
                assertFalse(generate()!!.isEnabled)

                val directorySummaries = mapOf(directory.id to Summary.plain("Directory overview."))
                canvas.updateCharts(ChartsCard(ReviewSummaries(tree, directorySummaries)))
                assertEquals("Directory overview.", summary()?.text)
                assertNull(generate())
                toggle("charts-lines-toggle:src").doClick()
                assertNull(summary())
                assertTrue(toggle("charts-lines-toggle:src").isSelected)
                assertFalse(toggle("charts-lines-summary-toggle:src").isSelected)
                assertTrue(descendants(canvas).any { it.name == "hierarchy-preview:$path" })

                descendants(canvas).filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src" }.doClick()
                toggle("charts-lines-summary-toggle:$path").doClick()
                generate()!!.doClick()
                assertEquals(listOf("src" to SummaryKind.DIRECTORY, path to SummaryKind.FILE), requested)
                canvas.updateCharts(ChartsCard(ReviewSummaries(tree, directorySummaries +
                    (file.id to Summary.plain("Captured source overview.")))))
                assertEquals("Captured source overview.", summary()?.text)
                val plainSize = firstWordSize()
                tagsCloud().doClick()
                assertTrue(firstWordSize() > plainSize)
                assertEquals("Captured source overview.", summary()?.text)
                assertFalse(descendants(canvas).any { it.name == "hierarchy-preview:$path" })
                toggle("charts-lines-summary-toggle:$path").doClick()
                assertNull(summary())
                toggle("charts-lines-summary-toggle:$path").doClick()
                assertTrue(tagsCloud().isSelected)
            } finally { canvas.dispose() }
        }
    }

    fun testFoldingCursorKeyboardAndDividerSurviveRebuilds() {
        val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { CodeFile(it, "/repo/$it", if (it == "src/nested/b.kt") "one" else "one\ntwo\n") }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, project = project, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun all() = descendants(canvas)
        fun panel() = all().filterIsInstance<LineChartPanel>().single()
        fun rows() = all().mapNotNull { it.name?.takeIf { n -> n.startsWith("charts-lines-row:") }?.removePrefix("charts-lines-row:") }
        fun key(id: String) = panel().let { it.actionMap.get(id).actionPerformed(java.awt.event.ActionEvent(it, 0, id)) }
        fun fold(path: String) = all().filterIsInstance<JLabel>().single { it.name == "charts-lines-fold:$path" }
        fun split() = all().filterIsInstance<javax.swing.JSplitPane>().single { it.name == "hierarchy-preview-split" }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            assertEquals(listOf("readme.md", "src"), rows())
            assertEquals("readme.md", panel().cursorPath)
            assertTrue(all().none { it.name == "charts-lines-fold:readme.md" })

            // Clicking the chevron unfolds in place; the number column keeps its width for nested rows.
            fold("src").mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(fold("src"), 0, 0, 0, 1, 1, 1, false,
                java.awt.event.MouseEvent.BUTTON1)) }
            assertEquals(listOf("readme.md", "src", "src/nested", "src/a.kt"), rows())
            assertEquals(setOf("src"), canvas.chartsView.linesExpanded)
            assertSame(AllIcons.General.ChevronDown, fold("src").icon)

            key("charts-lines-down")
            assertEquals("src", panel().cursorPath)
            key("charts-lines-unfold")
            assertEquals("src/nested", panel().cursorPath)
            key("charts-lines-unfold")
            assertEquals(listOf("readme.md", "src", "src/nested", "src/nested/b.kt", "src/a.kt"), rows())
            key("charts-lines-down")
            assertEquals("src/nested/b.kt", panel().cursorPath)
            key("charts-lines-activate")
            assertEquals(listOf("src/nested/b.kt"), opened)
            key("charts-lines-fold")
            assertEquals("src/nested", panel().cursorPath)
            key("charts-lines-fold")
            assertEquals(listOf("readme.md", "src", "src/nested", "src/a.kt"), rows())

            // A moved divider, the cursor, and the folds survive a rebuild such as toggling a preview.
            split().apply { setSize(1400, 600); dividerLocation = 333 }
            assertEquals(333, canvas.chartsView.linesDivider)
            key("charts-lines-preview")
            assertEquals(LineChartPreview("src/nested", LineChartPreviewKind.SOURCE), canvas.chartsView.linesPreview)
            assertEquals(333, split().dividerLocation)
            assertEquals("src/nested", panel().cursorPath)
            assertEquals(listOf("readme.md", "src", "src/nested", "src/a.kt"), rows())

            // Enter explores a directory; going back up keeps the directory just left current.
            key("charts-lines-activate")
            assertEquals("src/nested", panel().directory.path)
            assertEquals(listOf("src/nested/b.kt"), rows())
            key("charts-lines-parent")
            assertEquals("src", panel().directory.path)
            assertEquals("src/nested", panel().cursorPath)
            key("charts-lines-fold")
            assertEquals("", panel().directory.path)
            assertEquals("src", panel().cursorPath)
            assertEquals(333, split().dividerLocation)
        } finally { canvas.dispose() }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
