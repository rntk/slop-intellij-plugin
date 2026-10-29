package dev.insight.diff

import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewedFiles
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.SummarySegment
import dev.insight.review.SummarySource.Claim
import dev.insight.review.SummarySource.Rows
import dev.insight.review.buildSummaryTree
import org.junit.Assert.*
import org.junit.Test
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.SegmentRef
import dev.insight.review.ui.SummaryLinks
import dev.insight.review.ui.SummaryMatch
import dev.insight.review.ui.SummaryTrace
import dev.insight.review.ui.SummaryTraceSession
import dev.insight.review.ui.SummaryTraceState
import dev.insight.review.ui.SummaryWord
import dev.insight.review.ui.TraceMark
import dev.insight.review.ui.installWordTracing
import dev.insight.review.ui.patchLines
import dev.insight.review.ui.selectionContext
import dev.insight.review.ui.selectionMenu
import dev.insight.review.ui.traceMarks
import dev.insight.review.ui.tracePath
import dev.insight.review.ui.traceStatus
import dev.insight.review.ui.levelsBelow

class ChangeSummaryTraceTest {
    /** Patch rows: 1 the hunk header, 2 the removed line, 3 the added line, 4 the context line. */
    private val patch = "@@ -1,2 +1,2 @@\n-old\n+new\n keep\n"
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", patch, "1", before = "old\nkeep\n", after = "new\nkeep\n")
    private fun segment(text: String, vararg sources: dev.insight.review.SummarySource) = SummarySegment(text, sources.toList())

    private val tree = buildSummaryTree(listOf("a/One.kt", "a/Two.kt", "b/Three.kt").map(::file))
    private fun node(identity: String) = tree.nodes.single { it.identity == identity }
    private val root = tree.root
    private val a = node("directory:a")
    private val b = node("directory:b")
    private val one = node("file:a/One.kt")
    private val two = node("file:a/Two.kt")
    private val three = node("file:b/Three.kt")

    /** Files cite their patch rows, folders their children's claims; `b` repeats its only file. */
    private val summaries = ReviewSummaries(tree)
        .summarize(mapOf(
            one.id to Summary(listOf(segment("Adds retries", Rows(3, 3)), segment(" and "), segment("logs failures.", Rows(2, 2)))),
            two.id to Summary(listOf(segment("Caches responses.", Rows(3, 4)))),
            three.id to Summary(listOf(segment("Renames the client.", Rows(2, 3)))),
        ))
        .summarize(mapOf(a.id to Summary(listOf(
            segment("Adds retries and caching", Claim(0, 0), Claim(1, 0)), segment(", "), segment("logging failures.", Claim(0, 2)),
        ))))
        .summarize(mapOf(root.id to Summary(listOf(
            segment("Makes requests resilient", Claim(0, 0)), segment(" and "), segment("renames the client.", Claim(1, 0)),
        ))))
    private val links = SummaryLinks(summaries)

    private fun wordIndex(id: Int, text: String) = SummaryWord.split(summaries.text(id)!!).first { it.text == text }.index

    @Test fun `split keeps identifiers whole excludes punctuation and records offsets`() {
        val text = "Adds `ChangeSummaryModel.kt` and read_diff_files to /summaries, the user's view."
        val words = SummaryWord.split(text)
        assertEquals(listOf("Adds", "ChangeSummaryModel.kt", "and", "read_diff_files", "to", "summaries", "the", "user's", "view"), words.map { it.text })
        words.forEachIndexed { index, word ->
            assertEquals(index, word.index)
            assertEquals(word.text, text.substring(word.start, word.end))
        }
        assertEquals(listOf(true, true, false, true, false, true, false, true, true), words.map { it.meaningful })
        assertTrue(SummaryWord.split("…, — !").isEmpty())
    }

    @Test fun `a selection widens to whole words and is its own query`() {
        val summary = "Deletes the canvas\nviews and the tests."
        // "he canv" starts inside "the" and ends inside "canvas".
        val context = selectionContext("root", summary, summary.indexOf("he canv"), summary.indexOf("he canv") + 7)!!
        assertEquals("the canvas", context.query)
        assertEquals(listOf("the", "canvas"), context.context.map { it.text })
        assertEquals("canvas", SummaryWord.split(summary)[context.word.index].text)
        assertEquals("canvas views", selectionContext("root", summary, summary.indexOf("canvas"), summary.indexOf("views") + 5)!!.query)
        assertNull("Only filler words", selectionContext("root", summary, summary.indexOf("and"), summary.indexOf("and the") + 7))
    }

    @Test fun `citations link each segment to the child segments it condenses, both ways`() {
        assertEquals(listOf(SegmentRef(one.id, 0), SegmentRef(two.id, 0)), links.below(SegmentRef(a.id, 0)))
        assertEquals(listOf(SegmentRef(a.id, 0)), links.above(SegmentRef(one.id, 0)))
        // A folder with one entry repeats its summary, citing it segment by segment.
        assertEquals(summaries.text(three.id), summaries.text(b.id))
        assertEquals(listOf(SegmentRef(three.id, 0)), links.below(SegmentRef(b.id, 0)))
        assertEquals(setOf(SegmentRef(a.id, 0), SegmentRef(one.id, 0), SegmentRef(two.id, 0)), links.descendants(listOf(SegmentRef(root.id, 0))))
        assertEquals(setOf(SegmentRef(a.id, 2)), links.ancestors(listOf(SegmentRef(one.id, 2))))
        assertFalse("Connecting text cites nothing", links.linked(SegmentRef(a.id, 1)))
        assertEquals(listOf(Rows(3, 3)), links.rows(SegmentRef(one.id, 0)))
    }

    @Test fun `a word's context is its cited segment, and connecting words have none`() {
        val context = links.context(a, wordIndex(a.id, "caching"))!!
        assertEquals("Adds retries and caching", context.query)
        assertEquals(listOf("Adds", "retries", "and", "caching"), context.context.map { it.text })
        assertEquals("caching", context.word.text)
        assertNull("A stop word is not traced", links.context(root, wordIndex(root.id, "and")))
        assertEquals("logging failures.", links.context(a, wordIndex(a.id, "failures"))!!.query)
    }

    @Test fun `search marks the cited summaries in the levels below as related and the rest of the window as not`() {
        val trace = links.search(root.id, links.context(root, wordIndex(root.id, "resilient"))!!)
        assertEquals(listOf(a.identity, one.identity, two.identity), trace.related.map { it.identity })
        // b/ only repeats its single file, so the search passes through it.
        assertEquals(setOf(three.identity), trace.matches.filter { !it.related }.map { it.identity }.toSet())
        assertEquals(a.identity, trace.best?.identity)
        assertEquals(listOf(1, 2, 2), trace.related.map { it.levelsBelow })
        assertEquals(4, trace.candidates)
    }

    @Test fun `a match at the edge of the search carries its cited segments on`() {
        val trace = links.search(root.id, links.context(root, wordIndex(root.id, "resilient"))!!, levels = 1)
        val next = trace.related.single().next!!
        assertEquals(a.identity, next.identity)
        assertEquals("Adds retries and caching", next.query)
        val further = links.search(a.id, next)
        assertEquals(listOf(one.identity, two.identity), further.related.map { it.identity })
    }

    @Test fun `searching a single word after a multi-segment selection replaces the selection context`() {
        val state = object : SummaryTraceState() {
            override fun showTrace(tree: ReviewSummaryTree, trace: SummaryTrace) {}
        }
        val session = SummaryTraceSession(state, { summaries }) {}
        val rootText = summaries.text(root.id)!!
        session.searchSelection(root.identity, 0, rootText.length)
        assertEquals(setOf(a.identity, one.identity, two.identity, three.identity), state.tracing!!.trace!!.related.map { it.identity }.toSet())
        session.trace(root.identity, wordIndex(root.id, "renames"), search = true)
        assertEquals("renames the client.", state.tracing!!.context.query)
        assertEquals(listOf(three.identity), state.tracing!!.trace!!.related.map { it.identity })
    }

    @Test fun `hovering a word inside a searched selection switches to its segment context`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val state = object : SummaryTraceState() {
                override fun showTrace(tree: ReviewSummaryTree, trace: SummaryTrace) {}
            }
            val session = SummaryTraceSession(state, { summaries }) {}
            val rootText = summaries.text(root.id)!!
            val area = javax.swing.JTextArea(rootText).apply { setSize(800, 100) }
            installWordTracing(area, root.identity, rootText, state, session::trace, session::searchSelection)
            area.select(0, rootText.length)
            session.searchSelection(root.identity, 0, rootText.length)
            // Use the selection's first word too: its index alone does not identify a segment trace.
            val word = SummaryWord.split(rootText).first()
            val start = area.modelToView2D(word.start)
            val end = area.modelToView2D(word.end)
            val event = java.awt.event.MouseEvent(area, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0,
                ((start.x + end.x) / 2).toInt(), start.centerY.toInt(), 0, false)
            area.mouseMotionListeners.forEach { it.mouseMoved(event) }
            assertEquals("Makes requests resilient", state.tracing!!.context.query)
            assertNull("Hover clears the selection search results", state.tracing!!.trace)
            assertTrue("The manual selection keeps its citation echo", state.echo!!.selection)
        }
    }

    @Test fun `a segment's targets are the files and folders it cites at any depth`() {
        val resilient = links.context(root, wordIndex(root.id, "resilient"))!!
        assertEquals(listOf(one.identity, two.identity), links.targets(root.id, resilient, SummaryKind.FILE).map { it.identity })
        assertEquals(listOf(a.identity), links.targets(root.id, resilient, SummaryKind.DIRECTORY).map { it.identity })
        val renames = links.context(root, wordIndex(root.id, "renames"))!!
        assertEquals(listOf(three.identity), links.targets(root.id, renames, SummaryKind.FILE).map { it.identity })
        val logging = links.context(a, wordIndex(a.id, "logging"))!!
        assertEquals(listOf(one.identity), links.targets(a.id, logging, SummaryKind.FILE).map { it.identity })
        assertTrue(links.targets(a.id, logging, SummaryKind.DIRECTORY).isEmpty())
    }

    @Test fun `To file and To directory mark every cited target and select the chosen one`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val shown = mutableListOf<SummaryTrace>()
            val state = object : SummaryTraceState() {
                override fun showTrace(tree: ReviewSummaryTree, trace: SummaryTrace) { shown += trace }
            }
            val session = SummaryTraceSession(state, { summaries }) {}
            val rootText = summaries.text(root.id)!!
            val area = javax.swing.JTextArea(rootText).apply { setSize(800, 100) }
            installWordTracing(area, root.identity, rootText, state, session::trace, session::searchSelection)
            area.caretPosition = rootText.indexOf("resilient")
            val menu = selectionMenu(area)!!
            val items = menu.components.filterIsInstance<javax.swing.JMenuItem>().associateBy { it.name }
            val files = items.getValue("to-file") as javax.swing.JMenu
            assertEquals(listOf("a/One.kt", "a/Two.kt"), files.menuComponents.map { (it as javax.swing.JMenuItem).text })
            assertFalse("A single folder needs no submenu", items.getValue("to-directory") is javax.swing.JMenu)

            (files.menuComponents[1] as javax.swing.JMenuItem).doClick()
            val trace = shown.single()
            assertEquals(two.identity, trace.best?.identity)
            assertEquals(listOf(two.identity, one.identity), trace.related.map { it.identity })
            assertEquals(listOf(2, 2), trace.related.map { it.levelsBelow })
            assertEquals(SummaryKind.FILE, state.tracing!!.target)
            assertTrue(traceStatus(state.tracing!!).contains("cites 2 files"))

            items.getValue("to-directory").doClick()
            assertEquals(listOf(a.identity), shown.last().related.map { it.identity })
            assertTrue(traceStatus(state.tracing!!).contains("cites 1 folder,"))
        }
    }

    @Test fun `a file's segment opens the patch lines it cites on their side`() {
        val added = links.searchLines(one.id, links.context(one, wordIndex(one.id, "retries"))!!)
        assertEquals(listOf("+1"), added.lines.map { "${it.marker}${it.number}" })
        val removed = links.searchLines(one.id, links.context(one, wordIndex(one.id, "failures"))!!)
        assertEquals(listOf(LineSide.BEFORE), removed.lines.map { it.side })
        val both = links.searchLines(two.id, links.context(two, wordIndex(two.id, "Caches"))!!)
        assertEquals(listOf("+1", " 2"), both.lines.map { "${it.marker}${it.number}" })
    }

    @Test fun `hover echoes the linked segments below and above, but not the unrelated ones`() {
        val state = object : SummaryTraceState() {
            override fun showTrace(tree: ReviewSummaryTree, trace: SummaryTrace) {}
        }
        state.links = { links }
        val retries = SummaryWord.split(summaries.text(one.id)!!).first { it.text == "retries" }
        state.echo(one.identity, listOf(retries))
        val echo = state.echo!!
        assertEquals(listOf(0), echo.segments)
        assertEquals(setOf(a.identity, root.identity), echo.ranges.keys)
        val aText = summaries.text(a.id)!!
        assertEquals(listOf("Adds retries and caching"), echo.ranges.getValue(a.identity).map { aText.substring(it.first, it.last + 1) })
        state.echo(root.identity, SummaryWord.split(summaries.text(root.id)!!).filter { it.text == "renames" })
        assertEquals(setOf(b.identity, three.identity), state.echo!!.ranges.keys)
    }

    @Test fun `reviewed files dim exactly the segments that cite only them`() {
        fun dimmed(reviewed: List<ReviewFile>, id: Int): String {
            val words = links.dimming(reviewed.mapTo(hashSetOf(), ReviewedFiles::key))[id].orEmpty()
            return SummaryWord.split(summaries.text(id)!!).filter { it.index in words }.joinToString(" ") { it.text }
        }
        val first = listOf(one.file as ReviewFile)
        assertEquals("logging failures", dimmed(first, a.id))
        assertEquals("", dimmed(first, root.id))
        val both = listOf(one.file as ReviewFile, two.file as ReviewFile)
        assertEquals("Adds retries and caching logging failures", dimmed(both, a.id))
        // The connecting "and" stays bright between a reviewed and an unreviewed part.
        assertEquals("Makes requests resilient", dimmed(both, root.id))
        val all = both + (three.file as ReviewFile)
        assertEquals("Makes requests resilient and renames the client", dimmed(all, root.id))
    }

    @Test fun `a cited summary without citations of its own keeps the claim bright until all its files are reviewed`() {
        val summaries = ReviewSummaries(tree)
            .summarize(mapOf(
                one.id to Summary(listOf(segment("Adds retries.", Rows(3, 3)))),
                two.id to Summary(listOf(segment("Caches responses.", Rows(3, 4)))),
                three.id to Summary(listOf(segment("Renames the client.", Rows(2, 3)))),
            ))
            .summarize(mapOf(a.id to Summary.plain("Adds retries and caching.")))
            .summarize(mapOf(root.id to Summary(listOf(segment("Reworks the client.", Claim(0, 0), Claim(1, 0))))))
        val links = SummaryLinks(summaries)
        fun dimmed(reviewed: List<ReviewFile>) = links.dimming(reviewed.mapTo(hashSetOf(), ReviewedFiles::key))[root.id].orEmpty()
        assertTrue(dimmed(listOf(three.file as ReviewFile)).isEmpty())
        assertTrue(dimmed(listOf(one.file as ReviewFile, three.file as ReviewFile)).isEmpty())
        assertFalse(dimmed(listOf(one, two, three).map { it.file as ReviewFile }).isEmpty())
    }

    @Test fun `trace path opens down to every match and folds related branches with nothing related beneath`() {
        val tree = buildSummaryTree(listOf("a/x/One.kt", "a/x/Two.kt", "a/y/Three.kt", "a/y/Four.kt", "b/Five.kt", "b/Six.kt").map(::file))
        fun node(identity: String) = tree.nodes.single { it.identity == identity }
        val root = tree.root
        val a = node("directory:a")
        val x = node("directory:a/x")
        val y = node("directory:a/y")
        val three = node("file:a/y/Three.kt")
        val word = SummaryWord(0, 0, 1, "w")
        val trace = SummaryTrace(root.identity, "w", word, listOf(word), "w", listOf(
            SummaryMatch(x.id, x.identity, true, 2), SummaryMatch(three.id, three.identity, true, 3),
            SummaryMatch(a.id, a.identity, true, 1), SummaryMatch(node("directory:b").id, "directory:b", false, 1),
        ))
        val path = tracePath(tree, trace)!!
        assertEquals(setOf(root.identity, a.identity, x.identity, y.identity, three.identity), path.path)
        // A related folder that leads to another match stays open; one with nothing related beneath folds to show its summary.
        assertEquals(setOf(root.identity, a.identity, y.identity), path.open)
        assertEquals(setOf(x.identity), path.fold)
        val marks = traceMarks(tree, trace)
        assertEquals(TraceMark.TRACED, marks[root.identity])
        assertEquals(TraceMark.BEST, marks[x.identity])
        assertEquals(TraceMark.RELATED, marks[a.identity])
        assertEquals(TraceMark.RELATED, marks[three.identity])
        assertEquals(TraceMark.PATH, marks[y.identity])
        assertFalse("Unrelated nodes are not marked", "directory:b" in marks)
    }

    @Test fun `patch lines open removed lines before and the rest after, or in the patch without both texts`() {
        val patch = "--- a/x.kt\n+++ b/x.kt\n@@ -3,3 +3,3 @@ fun a()\n keep\n-old\n+new\n\\ No newline at end of file\n@@ -10 +10,2 @@\n+added\n same\n"
        val lines = patchLines(patch)
        assertEquals(listOf(" 3", "-4", "+4", "+10", " 11"), lines.map { "${it.marker}${it.number}" })
        assertEquals(listOf(LineSide.AFTER, LineSide.BEFORE, LineSide.AFTER, LineSide.AFTER, LineSide.AFTER), lines.map { it.side })
        assertEquals(listOf(0, 0, 0, 1, 1), lines.map { it.hunk })
        assertEquals(listOf(4, 5, 6, 9, 10), patchLines(patch, inPatch = true).map { it.number })
    }
}
