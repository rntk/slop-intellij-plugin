package dev.insight.review

import dev.insight.codereview.CodeFile
import org.junit.Assert.*
import org.junit.Test

class ReviewTreeBoardTest {
    private fun file(path: String, text: String, counts: RadarSymbols? = null) =
        CodeFile(path, "/repo/$path", text, symbolCounts = counts)

    @Test fun `flattens whole tree expanded by default with depths and sibling continuation flags`() {
        val tree = buildSummaryTree(listOf(
            file("src/a.kt", "1\n2\n3\n"),
            file("src/nested/b.kt", "1\n2\n"),
            file("docs/readme.md", "1\n"),
        ))
        val board = ReviewTreeBoard(ReviewLineChart(tree))
        assertEquals(
            listOf("" to 0, "src" to 1, "src/a.kt" to 2, "src/nested" to 2, "src/nested/b.kt" to 3, "docs" to 1, "docs/readme.md" to 2),
            board.nodes.map { it.entry.path to it.depth },
        )

        val src = board.nodes[board.indexOf("src")]
        assertFalse(src.isLastChild)
        assertTrue(src.collapsible)
        assertFalse(src.collapsed)
        assertEquals(emptyList<Boolean>(), src.ancestorHasNextSibling)

        val nestedB = board.nodes[board.indexOf("src/nested/b.kt")]
        assertTrue(nestedB.isLastChild)
        // Column 0 = `src` (has sibling `docs`), column 1 = `src/nested` (is last child of `src`).
        assertEquals(listOf(true, false), nestedB.ancestorHasNextSibling)

        val docs = board.nodes[board.indexOf("docs")]
        assertTrue(docs.isLastChild)
        assertFalse(board.nodes[board.indexOf("docs/readme.md")].collapsible)
    }

    @Test fun `collapsing directories hides descendants and computes collapsible paths and cursor fallback`() {
        val tree = buildSummaryTree(listOf(
            file("src/a.kt", "1\n2\n3\n"),
            file("src/nested/b.kt", "1\n2\n"),
            file("docs/readme.md", "1\n"),
        ))
        val chart = ReviewLineChart(tree)
        val board = ReviewTreeBoard(chart, collapsed = setOf("src"))

        assertEquals(listOf("", "src", "docs", "docs/readme.md"), board.nodes.map { it.entry.path })
        val src = board.nodes[board.indexOf("src")]
        assertTrue(src.collapsed)
        assertEquals(2, src.directChildCount)

        // Includes nested collapsible directory even when its parent is currently collapsed.
        assertEquals(setOf("src", "src/nested", "docs"), board.allCollapsiblePaths())
        assertEquals("src", board.cursorAfterCollapse("src/nested/b.kt", "src"))
        assertEquals("docs/readme.md", board.cursorAfterCollapse("docs/readme.md", "src"))
    }

    @Test fun `highlight covers ancestors, expanded descendants, connectors, and sibling trunk segments`() {
        val tree = buildSummaryTree(listOf(
            file("alpha/one.kt", "1\n2\n3\n4\n"),
            file("alpha/two.kt", "1\n2\n3\n"),
            file("beta/sub/deep.kt", "1\n2\n"),
            file("gamma/end.kt", "1\n"),
        ))
        val chart = ReviewLineChart(tree)
        val board = ReviewTreeBoard(chart)

        // Highlighting `beta/sub/deep.kt` highlights root -> beta -> beta/sub -> deep.kt.
        val deepIdx = board.indexOf("beta/sub/deep.kt")
        val deepHl = checkNotNull(board.highlight(deepIdx))
        assertEquals(
            setOf(board.indexOf(""), board.indexOf("beta"), board.indexOf("beta/sub"), deepIdx),
            deepHl.ancestors,
        )
        assertTrue(deepHl.descendants.isEmpty())

        // On `alpha/one.kt` (above `beta` in root), column 0 (`depth - 1`) trunk continues down to `beta`.
        val alphaOne = board.nodes[board.indexOf("alpha/one.kt")]
        assertTrue(deepHl.verticalLineHighlighted(alphaOne, 0, board.nodes))
        assertFalse(deepHl.connectorHighlighted(alphaOne))

        // On `gamma/end.kt` (below `beta` in root), column 0 trunk is NOT highlighted.
        val gammaEnd = board.nodes[board.indexOf("gamma/end.kt")]
        assertFalse(deepHl.verticalLineHighlighted(gammaEnd, 0, board.nodes))

        val beta = board.nodes[board.indexOf("beta")]
        assertTrue(deepHl.connectorHighlighted(beta))
        assertFalse(deepHl.verticalLineHighlighted(beta, 0, board.nodes))

        // Highlighting an expanded directory includes its entire visible subtree.
        val alphaIdx = board.indexOf("alpha")
        val alphaHl = checkNotNull(board.highlight(alphaIdx))
        assertEquals(setOf(board.indexOf(""), alphaIdx), alphaHl.ancestors)
        assertEquals(setOf(board.indexOf("alpha/one.kt"), board.indexOf("alpha/two.kt")), alphaHl.descendants)
        val alphaOneNode = board.nodes[board.indexOf("alpha/one.kt")]
        assertTrue(alphaHl.connectorHighlighted(alphaOneNode))
        // `alpha/one.kt` has sibling `alpha/two.kt` which is also highlighted, so trunk down col 1 (depth - 1) continues.
        assertTrue(alphaHl.verticalLineHighlighted(alphaOneNode, 1, board.nodes))
        assertFalse(alphaHl.verticalLineHighlighted(board.nodes[board.indexOf("alpha/two.kt")], 1, board.nodes))
    }

    @Test fun `lenses and search filter hide empty and unmatched branches`() {
        val tree = buildSummaryTree(listOf(
            file("src/a.kt", "fun a() {}", RadarSymbols(mapOf(RadarSymbolKind.FUNCTION to 2))),
            file("src/b.kt", "class B", RadarSymbols(mapOf(RadarSymbolKind.CLASS to 1))),
            file("docs/readme.md", "hello"),
        ))
        val fnBoard = ReviewTreeBoard(ReviewLineChart(tree, LineChartMetric.lens(RadarSymbolKind.FUNCTION)))
        assertEquals(listOf("", "src", "src/a.kt"), fnBoard.nodes.map { it.entry.path })

        val searchBoard = ReviewTreeBoard(ReviewLineChart(tree, LineChartMetric.FOUND, only = setOf("src/b.kt")))
        assertEquals(listOf("", "src", "src/b.kt"), searchBoard.nodes.map { it.entry.path })
    }

    @Test fun `viewport pan, zoomAround, fitToBounds, and ensureRowVisible behave accurately`() {
        val viewport = TreeBoardViewport(panX = 10.0, panY = 20.0, zoom = 1.0)
        viewport.panBy(5.0, -4.0)
        assertEquals(15.0, viewport.panX, 1e-6)
        assertEquals(16.0, viewport.panY, 1e-6)

        // World point under screen (100, 80) remains at screen (100, 80) after zooming.
        val wxBefore = viewport.screenToWorldX(100.0)
        val wyBefore = viewport.screenToWorldY(80.0)
        viewport.zoomAround(100.0, 80.0, 2.0)
        assertEquals(2.0, viewport.zoom, 1e-6)
        assertEquals(100.0, viewport.worldToScreenX(wxBefore), 1e-6)
        assertEquals(80.0, viewport.worldToScreenY(wyBefore), 1e-6)

        viewport.fitToBounds(contentWidth = 1000.0, contentHeight = 500.0, viewWidth = 548, viewHeight = 348, padding = 24.0)
        assertEquals(0.5, viewport.zoom, 1e-6)

        viewport.ensureRowVisible(rowTopWorld = 1000.0, rowBottomWorld = 1024.0, viewHeight = 300, margin = 24.0)
        assertTrue(viewport.worldToScreenY(1024.0) <= 300.0 - 24.0 + 1e-6)
    }

    @Test fun `visibleFiles reduces tree to compact subtree while preserving structure and topology`() {
        val tree = buildSummaryTree(listOf(
            file("alpha/one.kt", "1\n2\n"),
            file("alpha/two.kt", "1\n"),
            file("beta/sub/deep.kt", "1\n"),
            file("beta/sub/other.kt", "1\n"),
            file("gamma/unused.kt", "1\n"),
        ))
        val chart = ReviewLineChart(tree)
        val focused = ReviewTreeBoard(chart, visibleFiles = setOf("alpha/two.kt", "beta/sub/deep.kt"))

        assertEquals(
            listOf("", "alpha", "alpha/two.kt", "beta", "beta/sub", "beta/sub/deep.kt"),
            focused.nodes.map { it.entry.path },
        )

        val alpha = focused.nodes[focused.indexOf("alpha")]
        assertFalse(alpha.isLastChild)
        assertEquals(1, alpha.directChildCount)
        assertTrue(alpha.collapsible)

        val alphaTwo = focused.nodes[focused.indexOf("alpha/two.kt")]
        assertTrue(alphaTwo.isLastChild)
        assertEquals(listOf(true), alphaTwo.ancestorHasNextSibling)

        val beta = focused.nodes[focused.indexOf("beta")]
        assertTrue(beta.isLastChild)

        val deep = focused.nodes[focused.indexOf("beta/sub/deep.kt")]
        assertTrue(deep.isLastChild)
        assertEquals(listOf(false, false), deep.ancestorHasNextSibling)

        assertEquals(setOf("alpha", "beta", "beta/sub"), focused.allCollapsiblePaths())
    }

    @Test fun `collectRelatedFiles resolves file and directory links and handles isolated or missing entries`() {
        val site = TreeConnectionSite(1, 1)
        val links = mapOf(
            "api/Api.kt" to TreeFileConnections(
                incoming = mapOf("client/Client.kt" to site),
                outgoing = mapOf("model/Model.kt" to site),
            ),
            "client/Client.kt" to TreeFileConnections(
                incoming = emptyMap(),
                outgoing = mapOf("api/Api.kt" to site),
            ),
            "model/Model.kt" to TreeFileConnections(
                incoming = mapOf("api/Api.kt" to site),
                outgoing = mapOf("model/Helper.kt" to site),
            ),
            "model/Helper.kt" to TreeFileConnections(
                incoming = mapOf("model/Model.kt" to site),
                outgoing = emptyMap(),
            ),
            "alone/Solo.kt" to TreeFileConnections(emptyMap(), emptyMap()),
        )

        val single = checkNotNull(collectRelatedFiles(listOf("api/Api.kt"), links))
        assertEquals(setOf("api/Api.kt"), single.targets)
        assertEquals(setOf("client/Client.kt"), single.incoming)
        assertEquals(setOf("model/Model.kt"), single.outgoing)
        assertEquals(setOf("api/Api.kt", "client/Client.kt", "model/Model.kt"), single.visibleFiles)

        // Focusing a directory keeps internal files as targets and excludes them from incoming/outgoing.
        val dir = checkNotNull(collectRelatedFiles(listOf("model/Model.kt", "model/Helper.kt"), links))
        assertEquals(setOf("model/Model.kt", "model/Helper.kt"), dir.targets)
        assertEquals(setOf("api/Api.kt"), dir.incoming)
        assertEquals(emptySet<String>(), dir.outgoing)
        assertEquals(setOf("model/Model.kt", "model/Helper.kt", "api/Api.kt"), dir.visibleFiles)

        val isolated = checkNotNull(collectRelatedFiles(listOf("alone/Solo.kt"), links))
        assertEquals(emptySet<String>(), isolated.linkedFiles)
        assertEquals(setOf("alone/Solo.kt"), isolated.visibleFiles)

        assertNull(collectRelatedFiles(listOf("missing/File.kt"), links))
        assertNull(collectRelatedFiles(emptyList(), links))
    }

    @Test fun `wrapSummaryLines wraps words, preserves paragraphs, and breaks long tokens`() {
        val measure: (String) -> Int = { it.length * 10 }
        assertEquals(emptyList<String>(), wrapSummaryLines("   \n\t ", 100, measure))
        assertEquals(listOf("Short summary."), wrapSummaryLines("Short summary.", 200, measure))
        assertEquals(
            listOf("Adds validation", "for user inputs", "Second part."),
            wrapSummaryLines("Adds  validation   for user inputs\r\n\r\nSecond part.", 150, measure),
        )
        assertEquals(
            listOf("abcde", "fghij", "kl"),
            wrapSummaryLines("abcdefghijkl", 50, measure),
        )
    }

    @Test fun `wrapSummaryText and lineLinkSpans preserve offsets across wrapped lines`() {
        val measure: (String) -> Int = { it.length * 10 }
        val text = "Calls `computeHelper` from src/pkg/Service.kt at L2."
        val wrapped = wrapSummaryText(text, 270, measure)
        assertEquals(listOf("Calls `computeHelper` from", "src/pkg/Service.kt at L2."), wrapped.map { it.text })

        val symStart = text.indexOf("computeHelper")
        val fileStart = text.indexOf("src/pkg/Service.kt")
        val lineStart = text.indexOf("L2")
        val matches = listOf(
            LineLinkMatch(symStart, symStart + "computeHelper".length, ReportLink(0, "computeHelper", ReportTargetKind.SYMBOL)),
            LineLinkMatch(fileStart, fileStart + "src/pkg/Service.kt".length, ReportLink(1, "src/pkg/Service.kt", ReportTargetKind.FILE, listOf("src/pkg/Service.kt"))),
            LineLinkMatch(lineStart, lineStart + 2, ReportLink(2, "L2", ReportTargetKind.FILE, listOf("src/A.kt"), lines = 2..2, contextual = true)),
        )
        val spans0 = lineLinkSpans(wrapped[0], matches)
        assertEquals(1, spans0.size)
        assertEquals("computeHelper", wrapped[0].text.substring(spans0[0].startCol, spans0[0].endCol))

        val spans1 = lineLinkSpans(wrapped[1], matches)
        assertEquals(2, spans1.size)
        assertEquals("src/pkg/Service.kt", wrapped[1].text.substring(spans1[0].startCol, spans1[0].endCol))
        assertEquals("L2", wrapped[1].text.substring(spans1[1].startCol, spans1[1].endCol))

        // A link spanning across a wrap boundary highlights its portion on both lines.
        val splitWrapped = wrapSummaryText(text, 100, measure)
        val fileSpans = splitWrapped.mapNotNull { line ->
            lineLinkSpans(line, listOf(matches[1])).firstOrNull()?.let { line.text.substring(it.startCol, it.endCol) }
        }
        assertEquals("src/pkg/Service.kt", fileSpans.joinToString(""))
    }
}
