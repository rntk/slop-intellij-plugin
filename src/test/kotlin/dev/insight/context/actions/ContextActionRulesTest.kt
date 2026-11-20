package dev.insight.context.actions

import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextScopeKind
import dev.insight.context.ContextSymbol
import dev.insight.review.ui.LineSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextActionRulesTest {
    @Test
    fun codeWallRequiresSourceAndDoesNotOfferItself() {
        val source = facts(context(ContextScopeKind.FILE, lines = null))
        assertTrue(canOpenCodeWall(source))
        assertFalse(canOpenCodeWall(source.copy(origin = ContextOrigin.CODE_WALL)))
        assertFalse(canOpenCodeWall(source.copy(hasReviewFile = true)))
        assertFalse(canOpenCodeWall(source.copy(context = source.context.copy(side = SourceSide.BEFORE))))
        assertFalse(canOpenCodeWall(source.copy(context = source.context.copy(kind = ContextScopeKind.CHANGE))))
    }
    private fun context(kind: ContextScopeKind, side: SourceSide = SourceSide.CURRENT, lines: IntRange? = 3..5, symbol: String? = null,
        path: String? = "src/A.kt") =
        CodeContext(kind, kind.name, path, path?.let { "/p/$it" }, lines, side, "text",
            symbol = symbol?.let { ContextSymbol("render", "A.render", it) })

    private fun facts(context: CodeContext, origin: ContextOrigin = ContextOrigin.EDITOR, file: Boolean = true, review: Boolean = false,
        canvas: Boolean = false) = ContextFacts(context, origin, file, review, canvas)

    /** The ids of the actions available for [facts], in the order of the rules below. */
    private fun available(f: ContextFacts) = buildList {
        if (canOpenInEditor(f)) add("editor")
        if (canOpenInIdeDiff(f)) add("diff")
        if (canOpenOnCanvas(f)) add("canvas")
        if (canShowCallGraph(f)) add("graph")
        if (canAddToFlowChart(f)) add("chart")
        if (canOpenSideBySide(f)) add("chain")
        if (canShowInSummaryTree(f)) add("tree")
    }

    @Test
    fun sourceScopesOfAProjectFile() {
        val all = ContextScopeKind.entries
        val expected = mapOf(
            ContextScopeKind.SELECTION to listOf("editor", "canvas", "chain"),
            ContextScopeKind.LINE to listOf("editor", "canvas", "chain"),
            ContextScopeKind.HUNK to listOf("editor", "chain"),
            ContextScopeKind.FILE_DIFF to listOf("editor", "chain"),
            ContextScopeKind.CHANGE to emptyList(),
            ContextScopeKind.DIRECTORY to listOf("canvas"),
            ContextScopeKind.FILE to listOf("editor", "canvas", "chain", "tree"),
        )
        for (kind in all - ContextScopeKind.SYMBOL) {
            val lines = if (kind == ContextScopeKind.FILE) null else 3..5
            val expectedIds = expected.getValue(kind).filter { lines != null || it != "chain" }
            assertEquals(kind.name, expectedIds, available(facts(context(kind, lines = lines))))
        }
    }

    @Test
    fun symbolScopesByKind() {
        assertEquals(listOf("editor", "canvas", "graph", "chart", "chain"), available(facts(context(ContextScopeKind.SYMBOL, symbol = "function"))))
        assertEquals(listOf("editor", "canvas", "chart", "chain"), available(facts(context(ContextScopeKind.SYMBOL, symbol = "class"))))
        assertEquals(listOf("editor", "canvas", "chart", "chain"), available(facts(context(ContextScopeKind.SYMBOL, symbol = "property"))))
        // A declaration of a language without call-graph support is neither graph nor chart material.
        assertEquals(listOf("editor", "canvas", "chain"), available(facts(context(ContextScopeKind.SYMBOL, symbol = "symbol"))))
    }

    @Test
    fun theRemovedSideOfADiffIsNotOnDisk() {
        for (kind in ContextScopeKind.entries) {
            val f = facts(context(kind, SourceSide.BEFORE, symbol = "function"), ContextOrigin.DIFFS_WALL, review = true)
            assertEquals(kind.name, emptyList<String>(), available(f).filterNot { it == "diff" || it == "canvas" })
        }
    }

    @Test
    fun anAfterSideBehavesLikeTheFileOnDisk() {
        val f = facts(context(ContextScopeKind.SYMBOL, SourceSide.AFTER, symbol = "function"), ContextOrigin.CODE_WALL)
        assertEquals(listOf("editor", "canvas", "graph", "chart", "chain"), available(f))
    }

    @Test
    fun reviewDiffScopesOfTheDiffsWall() {
        val wall = { kind: ContextScopeKind, canvas: Boolean ->
            available(facts(context(kind, SourceSide.AFTER, symbol = "function"), ContextOrigin.DIFFS_WALL, review = true, canvas = canvas))
        }
        assertEquals(listOf("editor", "diff", "canvas", "graph", "chart", "chain"), wall(ContextScopeKind.SYMBOL, true))
        // The canvas of a review diff needs the host's way of opening it.
        assertEquals(listOf("editor", "diff", "graph", "chart", "chain"), wall(ContextScopeKind.SYMBOL, false))
        assertEquals(listOf("editor", "diff", "canvas", "chain"), wall(ContextScopeKind.HUNK, true))
        assertEquals(listOf("editor", "diff", "canvas", "chain"), wall(ContextScopeKind.FILE_DIFF, true))
        val file = context(ContextScopeKind.FILE, SourceSide.AFTER, lines = null)
        assertEquals(listOf("editor", "diff", "canvas", "tree"), available(facts(file, ContextOrigin.DIFFS_WALL, review = true, canvas = true)))
        // The whole change has no single file to open.
        assertEquals(emptyList<String>(), wall(ContextScopeKind.CHANGE, true))
    }

    @Test
    fun theIdeDiffDoesNotOfferItself() {
        val f = facts(context(ContextScopeKind.HUNK, SourceSide.AFTER), ContextOrigin.IDE_DIFF, review = true)
        assertEquals(listOf("editor", "chain"), available(f))
    }

    @Test
    fun withoutAFileNothingOpens() {
        val f = facts(context(ContextScopeKind.SYMBOL, symbol = "function", path = null), file = false)
        assertEquals(emptyList<String>(), available(f))
    }

    @Test
    fun lineTraceFollowsTheContextSide() {
        val after = diffLineTrace("src/A.kt", context(ContextScopeKind.HUNK, SourceSide.AFTER, lines = 10..12))!!
        assertEquals(listOf(10, 11, 12), after.after)
        assertEquals(emptyList<Int>(), after.before)
        val before = diffLineTrace("src/A.kt", context(ContextScopeKind.SYMBOL, SourceSide.BEFORE, lines = 4..5))!!
        assertEquals(listOf(4, 5), before.before)
        assertEquals(LineSide.BEFORE, before.lines.first().side)
        assertNull(diffLineTrace("src/A.kt", context(ContextScopeKind.FILE_DIFF, lines = null)))
    }

    @Test
    fun chatInputGrowsBelowWhatIsTyped() {
        assertEquals("**ctx**", appendToChatInput("", "**ctx**"))
        assertEquals("**ctx**", appendToChatInput("  \n", "**ctx**"))
        assertEquals("why?\n\n**ctx**", appendToChatInput("why?\n", "**ctx**"))
    }
}
