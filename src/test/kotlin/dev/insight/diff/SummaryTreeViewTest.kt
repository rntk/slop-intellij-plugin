package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.*
import dev.insight.review.ui.*
import java.awt.Component
import java.awt.Container
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JTextField

class SummaryTreeViewTest : BasePlatformTestCase() {
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    private fun deepSummaries(paths: List<String> = listOf("a/b/c.kt", "other/d/e.kt")): ReviewSummaries {
        val files = paths.map {
            ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1", "old\n", "new\n")
        }
        val tree = buildSummaryTree(files)
        return ReviewSummaries(tree, tree.nodes.associate { node ->
            node.id to Summary(listOf(SummarySegment("Level ${node.id}",
                if (node.kind == SummaryKind.FILE) listOf(SummarySource.Rows(1, 3))
                else node.children.indices.map { SummarySource.Claim(it, 0) })))
        })
    }

    fun testLevelsUnfoldAllBranchesAndOverrideIndividualFolds() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState()
        fun panel(view: ChangeSummariesViewState) = ChangeSummaryTreePanel(SummariesCard(summaries, false, false),
            view, 1.0, ChangeSummaryActions({}, {}, {}, {}), project)
        fun buttons(panel: Container) = descendants(panel).filterIsInstance<JButton>()
            .filter { it.name?.startsWith("summary-tree:") == true }
        val panel = panel(state)
        val selector = descendants(panel.navigationControls(JTextField())).filterIsInstance<JComboBox<*>>().single()
        try {
            assertEquals(2, selector.selectedItem)
            assertEquals(3, selector.itemCount)
            selector.selectedItem = 3
            assertEquals(7, buttons(panel).size)
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            val root = summaryLinkTree(summaries)
            buttons(panel).single { it.name == "summary-tree:${root.children[0].key}" }.doClick()
            assertEquals(5, buttons(panel).size)
            selector.selectedItem = 1
            assertEquals(3, buttons(panel).size)
            selector.selectedItem = 3
            assertEquals(7, buttons(panel).size)
            assertTrue(state.treeFolded.isEmpty())
            selector.selectedItem = 1
            panel.zoomTo(1.2)
            assertEquals(3, buttons(panel).size)
            val restored = panel(state.fork())
            try {
                assertEquals(3, buttons(restored).size)
                val restoredSelector = descendants(restored.navigationControls(JTextField()))
                    .filterIsInstance<JComboBox<*>>().single()
                assertEquals(1, restoredSelector.selectedItem)
            } finally { restored.dispose() }
        } finally { panel.dispose() }
    }

    fun testTreeLevelControlSurvivesCardRefreshWithoutChangingFolderLevels() {
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.TREE
        }
        val content = CanvasState(emptyList(), summaries = SummariesCard(deepSummaries(), false, false))
        fun selector() = descendants(canvas).filterIsInstance<JComboBox<*>>()
            .single { it.name == "summary-tree-level-selector" }
        try {
            canvas.showNotes(content)
            val header = descendants(canvas).filterIsInstance<javax.swing.JPanel>()
                .single { it.name == "summaries-controls" }
            val controls = descendants(header).single { it.name == "summary-tree-controls" }
            assertSame(header, controls.parent)
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            for (width in listOf(840, 1000, 1200)) {
                header.setSize(width, header.preferredSize.height)
                layout(header)
                val field = descendants(header).filterIsInstance<JTextField>()
                    .single { it.name == "summary-tree-navigate" }
                assertTrue("Path field should fit at width $width: ${field.size}", field.width >= field.minimumSize.width)
                for (component in descendants(header).filter { it is JButton || it is JComboBox<*> || it === field }) {
                    val bounds = javax.swing.SwingUtilities.convertRectangle(component.parent, component.bounds, header)
                    assertTrue("Control should fit in header at width $width: $bounds", header.bounds.width >= bounds.x + bounds.width)
                    assertTrue(bounds.x >= 0)
                    assertTrue(bounds.y + bounds.height <= header.height)
                }
            }
            selector().selectedItem = 3
            assertEquals(1, canvas.summariesView.selectedLevel)
            canvas.showNotes(content)
            assertEquals(3, selector().selectedItem)
        } finally { canvas.dispose() }
    }

    fun testContextMenuUnfoldsOnlyItsSubtreeAndOptionallyAllDiffs() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState()
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        fun button(path: String): JButton {
            val id = summaries.tree.nodes.single { it.path == path }.id
            return descendants(panel).filterIsInstance<JButton>().single { it.text.contains("Level $id") }
        }
        fun menu(path: String, text: String) = button(path).componentPopupMenu.components
            .filterIsInstance<JMenuItem>().single { it.text == text }
        try {
            menu("a", "Unfold all").doClick()
            assertNotNull(button("a/b/c.kt"))
            assertTrue(state.treeOpenedFiles.isEmpty())
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            assertTrue(descendants(panel).filterIsInstance<JButton>().none {
                it.text.contains("Level ${summaries.tree.nodes.single { it.path == "other/d/e.kt" }.id}")
            })
            // Explicit folds are also cleared when unfolding a subtree again.
            button("a/b").doClick()
            menu("a", "Unfold all source/diff").doClick()
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            assertNotNull(button("a/b/c.kt"))
            menu(summaries.tree.root.path, "Unfold all source/diff").doClick()
            assertEquals(2, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            val editors = descendants(panel).filterIsInstance<DiffFileEditor>()
            menu(summaries.tree.root.path, "Unfold all source/diff").doClick()
            assertEquals(editors.toSet(), descendants(panel).filterIsInstance<DiffFileEditor>().toSet())
        } finally { panel.dispose() }
    }

    private fun drag(component: Component, dx: Int, dy: Int) {
        fun event(id: Int, x: Int, y: Int) = java.awt.event.MouseEvent(component, id, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
            2, 2, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1)
        component.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_PRESSED, 100, 100))
        component.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_DRAGGED, 100 + dx, 100 + dy))
        component.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_RELEASED, 100 + dx, 100 + dy))
    }

    fun testFullSummaryBlockOpensOnTopMovesAndSurvivesRebuilds() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState()
        fun panel(view: ChangeSummariesViewState) = ChangeSummaryTreePanel(SummariesCard(summaries, false, false),
            view, 1.0, ChangeSummaryActions({}, {}, {}, {}), project)
        val panel = panel(state)
        fun button(path: String): JButton {
            val id = summaries.tree.nodes.single { it.path == path }.id
            return descendants(panel).filterIsInstance<JButton>().single { it.text.contains("Level $id") }
        }
        fun block(panel: Container) = descendants(panel).filterIsInstance<FullSummaryBlock>().single()
        try {
            button("a").componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == "Full summary" }.doClick()
            val block = block(panel)
            assertSame(panel, block.parent)
            assertEquals(0, panel.getComponentZOrder(block))
            // The merged folder reads as the folder it shows as, beneath the root.
            val entries = descendants(block).filter { it.name?.startsWith("summary-full-entry:") == true }
            assertEquals(listOf(summaries.tree.root.identity, summaries.tree.nodes.single { it.path == "a/b" }.identity),
                entries.map { it.name!!.removePrefix("summary-full-entry:") })
            val texts = descendants(block).filterIsInstance<WrappedSummaryText>()
            assertEquals(2, texts.size)
            assertTrue(texts.all { it.highlighter.highlights.isNotEmpty() })

            val before = java.awt.Point(block.location)
            val bounds = state.treeSummaryBlocks.values.single()
            val stored = java.awt.Point(bounds.location)
            drag(descendants(block).single { it.name?.startsWith("summary-full-header:") == true }, 60, 30)
            assertEquals(java.awt.Point(before.x + 60, before.y + 30), block.location)
            assertEquals(java.awt.Point(stored.x + 60, stored.y + 30), bounds.location)
            assertTrue(panel.preferredSize.width >= block.x + block.width)

            drag(descendants(block).single { it.name?.startsWith("summary-full-resize:") == true }, 100, 0)
            assertEquals(620, bounds.width)
            val size = java.awt.Dimension(block.size)
            val font = descendants(block).filterIsInstance<WrappedSummaryText>().first().font.size2D
            panel.zoomTo(0.5)
            assertSame(block, block(panel))
            // Zooming moves the block with the tree but keeps it readable.
            assertEquals(size, block.size)
            assertEquals(font, descendants(block).filterIsInstance<WrappedSummaryText>().first().font.size2D)
            assertEquals(java.awt.Point(bounds.x / 2, bounds.y / 2), block.location)
            val restored = panel(state.fork())
            try { assertEquals(block.key, block(restored).key) } finally { restored.dispose() }

            descendants(block).filterIsInstance<com.intellij.ui.InplaceButton>().single().doClick()
            assertTrue(state.treeSummaryBlocks.isEmpty())
            assertTrue(descendants(panel).none { it is FullSummaryBlock })
        } finally { panel.dispose() }
    }

    fun testSourceAndDiffEditorsMoveAwayFromTheirLeafAndDockBack() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState()
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        try {
            assertTrue(panel.navigate("a/b/c.kt", true))
            val editor = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            val frame = editor.parent
            val docked = java.awt.Point(frame.location)
            val handle = descendants(frame as Container).single { it.name?.startsWith("summary-diff-move:") == true }
            drag(handle, 200, 40)
            assertEquals(java.awt.Point(docked.x + 200, docked.y + 40), frame.location)
            drag(handle, 10, 0)
            assertEquals(java.awt.Point(docked.x + 210, docked.y + 40), frame.location)
            assertEquals(1, state.treeDiffPositions.size)
            assertSame(editor, descendants(panel).filterIsInstance<DiffFileEditor>().single())
            assertEquals(0, panel.getComponentZOrder(frame))
            handle.dispatchEvent(java.awt.event.MouseEvent(handle, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0,
                2, 2, 2, false, java.awt.event.MouseEvent.BUTTON1))
            assertTrue(state.treeDiffPositions.isEmpty())
            assertEquals(docked, frame.location)
        } finally { panel.dispose() }
    }

    fun testNavigateControlsOpenOnlyTheRequestedPathAndKeepUnrelatedFolds() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState()
        val root = summaryLinkTree(summaries)
        val unrelated = root.children[1].key
        state.treeFolded.add(root.key)
        state.treeFolded.add(unrelated)
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        val field = JTextField("a/b/c.kt")
        val controls = panel.navigationControls(field)
        fun click(text: String) = descendants(controls).filterIsInstance<JButton>().single { it.text == text }.doClick()
        try {
            click("Unfold")
            assertFalse(state.treeFolded.contains(root.key))
            assertTrue(state.treeFolded.contains(unrelated))
            assertEquals(root.pathKeys(field.text).filter { it != root.children[0].children.single().children.single().key }.toSet(),
                state.treeExpanded)
            assertTrue(state.treeOpenedFiles.isEmpty())
            field.text = "/repo/a/b/c.kt"
            click("Unfold source/diff")
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            assertEquals(1, state.treeOpenedFiles.size)
            val expanded = state.treeExpanded.toSet()
            val opened = state.treeOpenedFiles.toSet()
            field.text = "missing.kt"
            click("Unfold source/diff")
            assertEquals(expanded, state.treeExpanded)
            assertEquals(opened, state.treeOpenedFiles)
            assertTrue(descendants(controls).filterIsInstance<javax.swing.JLabel>().any { it.text == "Path not found in this tree" })
        } finally { panel.dispose() }
    }

    fun testNavigationKeepsEarlierBranchesAndEditorsOpen() {
        val summaries = deepSummaries()
        val state = ChangeSummariesViewState().apply { treeLevel = 1 }
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        val field = JTextField("a/b/c.kt")
        val controls = panel.navigationControls(field)
        fun go() = descendants(controls).filterIsInstance<JButton>().single { it.text == "Unfold source/diff" }.doClick()
        try {
            go()
            val first = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            val expanded = state.treeExpanded.toSet()
            val opened = state.treeOpenedFiles.toSet()
            field.text = "other/d/e.kt"
            go()
            assertTrue(state.treeExpanded.containsAll(expanded))
            assertTrue(state.treeOpenedFiles.containsAll(opened))
            assertEquals(2, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            assertTrue(descendants(panel).contains(first))
            field.text = "a/b/c.kt"
            go()
            assertEquals(2, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            assertTrue(descendants(panel).contains(first))
            val restored = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state.fork(), 1.0,
                ChangeSummaryActions({}, {}, {}, {}), project)
            try {
                assertEquals(2, descendants(restored).filterIsInstance<DiffFileEditor>().size)
            } finally { restored.dispose() }
        } finally { panel.dispose() }
    }

    fun testNavigationDoesNotUnfoldHiddenSiblingsAtAnyLevel() {
        val summaries = deepSummaries(listOf("a/b/c.kt", "a/unrelated/deep/x.kt", "other/d/e.kt"))
        val root = summaryLinkTree(summaries)
        val state = ChangeSummariesViewState().apply {
            treeLevel = 100
            treeFolded.add(root.key)
        }
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        val route = root.pathKeys("a/b/c.kt")
        fun expected(node: SummaryLinkNode): Set<String> = setOf(node.key) +
            if (node.key in route) node.children.flatMap { expected(it) }.toSet() else emptySet()
        try {
            assertTrue(panel.navigate("a/b/c.kt", true))
            val visible = descendants(panel).filterIsInstance<JButton>()
                .mapNotNull { it.name?.takeIf { name -> name.startsWith("summary-tree:") }?.removePrefix("summary-tree:") }.toSet()
            assertEquals(expected(root), visible)
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            assertTrue(state.treeExpanded.all { it in route })
        } finally { panel.dispose() }
    }

    fun testNavigationOpensEverySourceOccurrenceAndPreservesEditors() {
        val file = object : ReviewSource {
            override val path = "src/a.kt"
            override val absolutePath = "/repo/src/a.kt"
            override val text = "fun example() = 1\n"
            override val fingerprint = path
            override val facts = emptyList<String>()
        }
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree, tree.nodes.associate { node ->
            node.id to if (node.kind == SummaryKind.FILE) Summary(listOf(
                SummarySegment("First claim", listOf(SummarySource.Rows(1, 1))),
                SummarySegment("Second claim", listOf(SummarySource.Rows(1, 1))),
            )) else Summary(listOf(
                SummarySegment("First route", listOf(SummarySource.Claim(0, 0))),
                SummarySegment("Second route", listOf(SummarySource.Claim(0, 1))),
            ))
        })
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), ChangeSummariesViewState(), 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project, ReviewVocabulary.CODE)
        try {
            assertTrue(panel.navigate(file.path, false))
            assertTrue(descendants(panel).filterIsInstance<DiffSourceEditor>().isEmpty())
            assertTrue(panel.navigate(file.absolutePath, true))
            val editors = descendants(panel).filterIsInstance<DiffSourceEditor>()
            assertEquals(2, editors.size)
            assertTrue(editors.all { it.editor.document.text == file.text })
            panel.navigate(file.path, true)
            assertEquals(editors.toSet(), descendants(panel).filterIsInstance<DiffSourceEditor>().toSet())
        } finally { panel.dispose() }
    }

    fun testNavigateFieldIsSeparateFromFilteringAndSurvivesCardRefresh() {
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.TREE
        }
        val content = CanvasState(emptyList(), summaries = SummariesCard(deepSummaries(), false, false))
        fun field() = descendants(canvas).filterIsInstance<JTextField>().single { it.name == "summary-tree-navigate" }
        try {
            canvas.showNotes(content)
            field().text = "a/b/c.kt"
            descendants(canvas).filterIsInstance<JButton>().single { it.name == "summary-tree-navigate-unfold" }.doClick()
            assertEquals("", canvas.summariesView.fuzzy)
            assertTrue(canvas.summariesView.treeOpenedFiles.isEmpty())
            canvas.showNotes(content)
            assertEquals("a/b/c.kt", field().text)
        } finally { canvas.dispose() }
    }

    fun testDeepBranchesOpenOneLevelAtATimeAndKeepTheirState() {
        val file = ReviewFile("a/b/c/d.kt", "/repo/a/b/c/d.kt", null, "modified", "", "1")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree, tree.nodes.associate { node ->
            node.id to Summary(listOf(SummarySegment("Level ${node.id}", listOf(
                if (node.kind == SummaryKind.FILE) SummarySource.Rows(1, 1) else SummarySource.Claim(0, 0),
            ))))
        })
        val state = ChangeSummariesViewState()
        fun panel(state: ChangeSummariesViewState) = ChangeSummaryTreePanel(SummariesCard(summaries, false, false),
            state, 1.0, ChangeSummaryActions({}, {}, {}, {}), project)
        fun buttons(panel: Container) = descendants(panel).filterIsInstance<JButton>().filter { it.name?.startsWith("summary-tree:") == true }
        val panel = panel(state)
        try {
            assertEquals(3, buttons(panel).size)
            buttons(panel).single { it.text.contains("Level 2") }.doClick()
            assertEquals(4, buttons(panel).size)
            panel.zoomTo(1.2)
            assertEquals(4, buttons(panel).size)
            val restored = panel(state.fork())
            try { assertEquals(4, buttons(restored).size) } finally { restored.dispose() }
            buttons(panel).single { it.text.contains("Level 2") }.doClick()
            assertEquals(3, buttons(panel).size)
        } finally { panel.dispose() }
    }

    fun testSourceLeavesKeepMultipleSnapshotEditorsOnCanvas() {
        val files = listOf("a.kt", "b.kt").map { path -> object : ReviewSource {
            override val path = path
            override val absolutePath = "/repo/$path"
            override val text = "fun example() = 1\n"
            override val fingerprint = path
            override val facts = emptyList<String>()
        } }
        val summaries = ReviewSummaries(buildSummaryTree(files))
        var separateOpens = 0
        val state = ChangeSummariesViewState()
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}, showNode = { separateOpens++ }), project)
        fun button(path: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.contains(path) }
        try {
            assertTrue(descendants(panel).filterIsInstance<DiffSourceEditor>().isEmpty())
            button("a.kt").doClick()
            val first = descendants(panel).filterIsInstance<DiffSourceEditor>().single()
            button("b.kt").doClick()
            assertEquals(2, descendants(panel).filterIsInstance<DiffSourceEditor>().size)
            assertTrue(descendants(panel).contains(first))
            assertEquals(files[0].text, first.editor.document.text)
            assertTrue(first.editor.isViewer)
            val key = state.treeOpenedFiles.first { it.endsWith("/0/file") }
            panel.resizeDiff(key, 1000, 500)
            assertTrue(descendants(panel).contains(first))
            button("a.kt").doClick()
            assertTrue(first.editor.isDisposed)
            assertEquals(1, descendants(panel).filterIsInstance<DiffSourceEditor>().size)
            assertEquals(0, separateOpens)
        } finally { panel.dispose() }
    }

    fun testNodesShowTheirDirectoryAndReviewedBranchesDim() {
        val files = listOf("src/a.kt", "src/b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1", "old\n", "new\n") }
        val tree = buildSummaryTree(files)
        val src = tree.nodes.single { it.path == "src" }
        val a = tree.nodes.single { it.path == "src/a.kt" }
        val b = tree.nodes.single { it.path == "src/b.kt" }
        val summaries = ReviewSummaries(tree, mapOf(
            tree.root.id to Summary(listOf(SummarySegment("Whole change", listOf(SummarySource.Claim(0, 0), SummarySource.Claim(0, 1))))),
            src.id to Summary(listOf(
                SummarySegment("Cache", listOf(SummarySource.Claim(0, 0))),
                SummarySegment("Retries", listOf(SummarySource.Claim(1, 0))),
            )),
            a.id to Summary(listOf(SummarySegment("Reads storage", listOf(SummarySource.Rows(1, 3))))),
            b.id to Summary(listOf(SummarySegment("Repeats failures", listOf(SummarySource.Rows(1, 3))))),
        ))
        val marks = ReviewedFiles().apply { restore(files) }
        val review = SummaryReviewPresentation(summaries, marks, emptyMap()) { _, _ -> }
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), ChangeSummariesViewState(), 1.0,
            ChangeSummaryActions({}, {}, {}, {}, review = review), project)
        fun button(text: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.contains(text) }
        fun dimmed(text: String) = button(text).foreground == UIUtil.getContextHelpForeground()
        try {
            assertTrue(button("Cache").text.contains("<small>") && button("Cache").text.contains("src"))
            assertFalse(button("Whole change").text.contains("<small>"))
            assertFalse(dimmed("Cache"))
            marks.set(listOf("src/a.kt"), true)
            updateReviewPresentation(panel)
            assertTrue(dimmed("Cache"))
            assertFalse(dimmed("Retries"))
            assertFalse(dimmed("Whole change"))
            marks.set(listOf("src/b.kt"), true)
            updateReviewPresentation(panel)
            assertTrue(dimmed("Retries"))
            assertTrue(dimmed("Whole change"))
        } finally { panel.dispose() }
    }

    fun testBranchesFoldAndFileLeavesUnfoldActualDiffs() {
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1", "old\n", "new\n")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree, mapOf(
            0 to Summary(listOf(SummarySegment("Shared purpose", listOf(SummarySource.Claim(0, 0))))),
            tree.root.children.single() to Summary(listOf(SummarySegment("File behavior", listOf(SummarySource.Rows(1, 3))))),
        ))
        val state = ChangeSummariesViewState()
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        fun button(text: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.contains(text) }
        try {
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("a.kt").doClick()
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            val editor = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            val key = state.treeOpenedFiles.single()
            panel.resizeDiff(key, 1000, 500)
            assertSame(editor, descendants(panel).filterIsInstance<DiffFileEditor>().single())
            assertEquals(java.awt.Dimension(1000, 500), editor.parent.size)
            assertEquals(java.awt.Dimension(1000, 500), state.fork().treeDiffSizes[key])
            val before = panel.preferredSize
            panel.zoomTo(1.5)
            assertEquals(java.awt.Dimension(1500, 750), editor.parent.size)
            assertSame(editor, descendants(panel).filterIsInstance<DiffFileEditor>().single())
            assertTrue(panel.preferredSize.width > before.width)
            assertEquals(1.5, state.treeZoom)
            panel.zoomTo(100.0)
            assertEquals(3.0, panel.zoom)
            panel.actionMap.get("typed 0").actionPerformed(null)
            assertEquals(1.0, panel.zoom)
            button("a.kt").doClick()
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("Shared purpose").doClick()
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text.contains("File behavior") })
            button("Shared purpose").doClick()
            assertNotNull(button("File behavior"))
            button("a.kt").doClick()
            val reopened = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            assertEquals(java.awt.Dimension(1000, 500), reopened.parent.size)
            panel.resizeDiff(key, 0, 0)
            assertEquals(java.awt.Dimension(320, 160), reopened.parent.size)
        } finally { panel.dispose() }
    }

    fun testShowSummariesInTreeSwitchesModeAndUnfoldsOnlyTheRouteToTheFile() {
        val summaries = deepSummaries()
        val root = summaryLinkTree(summaries)
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900) }
        fun field() = descendants(canvas).filterIsInstance<JTextField>().single { it.name == "summary-tree-navigate" }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, false)))
            assertNotSame(ReviewSummariesMode.TREE, canvas.summariesView.mode)
            canvas.showSummariesInTree("/repo/a/b/c.kt")
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(ReviewSummariesMode.TREE, canvas.summariesView.mode)
            assertEquals("/repo/a/b/c.kt", field().text)
            assertTrue(canvas.summariesView.treeExpanded.isNotEmpty())
            val unrelated = root.pathKeys("other/d/e.kt") - root.pathKeys("a/b/c.kt")
            assertTrue(unrelated.isNotEmpty() && unrelated.none { it in canvas.summariesView.treeExpanded })
            // The request is spent: a later rebuild must not unfold again.
            canvas.summariesView.treeExpanded.clear()
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, false)))
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(canvas.summariesView.treeExpanded.isEmpty())
        } finally { canvas.dispose() }
    }

    fun testShowSummariesInTreeBeforeTheCardExistsAppliesWhenTheTreeAppears() {
        val summaries = deepSummaries()
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900) }
        try {
            canvas.showSummariesInTree("a/b/c.kt")
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, false)))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(ReviewSummariesMode.TREE, canvas.summariesView.mode)
            assertTrue(canvas.summariesView.treeExpanded.isNotEmpty())
        } finally { canvas.dispose() }
    }
}
