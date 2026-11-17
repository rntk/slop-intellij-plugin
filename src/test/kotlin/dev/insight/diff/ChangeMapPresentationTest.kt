package dev.insight.diff

import dev.insight.diff.ChangeMapFixture.lone
import dev.insight.diff.ChangeMapFixture.map
import dev.insight.diff.ChangeMapFixture.origin
import dev.insight.diff.ChangeMapFixture.readme
import dev.insight.diff.atoms.AtomEdge
import dev.insight.diff.atoms.AtomRelation
import dev.insight.diff.atoms.AtomRole
import dev.insight.diff.atoms.AtomStatus
import dev.insight.diff.atoms.ChangeCluster
import dev.insight.diff.atoms.ChangeGraph
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ClusterStep
import dev.insight.diff.atoms.buildChangeMapTree
import dev.insight.review.ReviewSummaries
import dev.insight.review.summarize
import org.junit.Assert.*
import org.junit.Test

class ChangeMapPresentationTest {
    private val files = ChangeMapFixture.files()
    private val tree = buildChangeMapTree(map, files)

    private fun presentation(summaries: ReviewSummaries = ReviewSummaries(tree), map: dev.insight.diff.atoms.ChangeMap? = ChangeMapFixture.map) =
        ChangeMapPresentation(ChangeMapCard(map, files, summaries, running = false, canRun = true))

    private fun id(identity: String) = tree.nodes.single { it.identity == identity }.id

    @Test fun `the header counts changes declarations and files and the button follows the map and its summaries`() {
        val empty = presentation(map = null)
        assertEquals("Change map", empty.header)
        assertEquals(ChangeMapAction.GENERATE, empty.action)
        assertTrue(empty.entries.isEmpty())
        assertNull(empty.detail("k1"))

        val fresh = presentation()
        assertEquals("Change map · 2 logical changes · 2 standalone · 7 declarations in 7 files", fresh.header)
        assertEquals(ChangeMapAction.FILL, fresh.action)
        assertEquals("0 / ${tree.nodes.size} summaries written", fresh.progress)
        assertNull(fresh.rootSummary)

        val complete = presentation(ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "About ${it.label}." }))
        assertEquals(ChangeMapAction.REGENERATE, complete.action)
        assertEquals("About All changes.", complete.rootSummary)
        assertNull(complete.progress)

        val nothing = presentation(ReviewSummaries(buildChangeMapTree(EMPTY_CHANGE_MAP, files)), EMPTY_CHANGE_MAP)
        assertTrue(nothing.empty)
        assertEquals("Change map · 0 logical changes · 0 standalone · 0 declarations in 0 files", nothing.header)
        assertEquals(ChangeMapAction.REGENERATE, nothing.action)
    }

    @Test fun `entries list the chains in map order then the standalone changes and the selection falls back to the first`() {
        val model = presentation()
        assertEquals(listOf(
            ChangeEntry("k1", "Make things", "+6 −6 · 3 declarations · 3 files"),
            ChangeEntry("k2", "Util.helper and 1 related", "+4 −4 · 2 declarations · 2 files", foundation = true),
            ChangeEntry(STANDALONE_ENTRY, "Standalone changes (2)", "+3 −3 · 2 declarations · 2 files"),
        ), model.entries)
        assertEquals("k2", model.selection("k2"))
        assertEquals(STANDALONE_ENTRY, model.selection(STANDALONE_ENTRY))
        assertEquals("k1", model.selection("gone"))
        assertEquals("k1", model.selection(null))
    }

    @Test fun `a chain reads from origin to test with relations to earlier steps and links to other changes`() {
        val detail = presentation().detail("k1")!!
        assertEquals("Make things", detail.title)
        assertNull(detail.summary)
        assertEquals(listOf("Foo.make", "Bar.use", "BarTest.uses"), detail.rows.map { it.name })
        assertEquals(listOf("Origin", "Follows", "Test"), detail.rows.map { it.role })
        assertEquals(listOf(0, 1, 2), detail.rows.map { it.depth })
        assertEquals(listOf(emptyList(), listOf("uses the new Foo.make"), listOf("tests Bar.use", "changes its use of Foo.make")),
            detail.rows.map { it.relations })
        val first = detail.rows[0]
        assertEquals("function · modified · signature changed", first.detail)
        assertEquals("src/Foo.kt:10-20", first.location)
        assertEquals("+2 −2", first.counts)
        assertEquals(listOf(ChangeLink(STANDALONE_ENTRY, "Standalone changes")), first.links)
        assertTrue(detail.rows.drop(1).all { it.links.isEmpty() })
    }

    @Test fun `standalone changes have no relations but link back to the chains they touch`() {
        val detail = presentation().detail(STANDALONE_ENTRY)!!
        assertEquals("Standalone changes", detail.title)
        assertEquals(listOf("Zed.zap", "README.md"), detail.rows.map { it.name })
        assertTrue(detail.rows.all { it.relations.isEmpty() && it.role == "Change" })
        assertEquals(listOf(ChangeLink("k1", "Make things")), detail.rows[0].links)
        assertEquals("whole file · modified", detail.rows[1].detail)
        assertEquals("README.md", detail.rows[1].location)
    }

    @Test fun `summaries are matched to the root chains and atoms by their tree identity`() {
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.filter { it.children.isEmpty() }.associate { it.id to "Leaf ${it.label}." })
            .summarize(mapOf(id("cluster:k1") to "Chain k1.", id("cluster:standalone") to "Loose ends."))
        val model = presentation(summaries)
        val chain = model.detail("k1")!!
        assertEquals("Chain k1.", chain.summary)
        assertEquals(listOf("Leaf Foo.make.", "Leaf Bar.use.", "Leaf BarTest.uses."), chain.rows.map { it.summary })
        assertNull(model.detail("k2")!!.summary)
        assertEquals("Loose ends.", model.detail(STANDALONE_ENTRY)!!.summary)
        assertEquals("Leaf Zed.zap.", model.detail(STANDALONE_ENTRY)!!.rows[0].summary)
        assertNull(model.rootSummary)
    }

    @Test fun `a chain is read by role and steps that follow one cause the same way fold into a fan-out`() {
        val callers = (1..5).map { ChangeMapFixture.atom("Caller$it.call") }
        val other = ChangeMapFixture.atom("Other.call")
        val test = ChangeMapFixture.atom("OriginTest.checks", "src/test/OriginTest.kt", evidenceKind = "test")
        val graph = ChangeGraph(listOf(origin) + callers + other + test,
            callers.map { AtomEdge(origin.id, it.id, AtomRelation.SIGNATURE_USE) } +
                AtomEdge(origin.id, other.id, AtomRelation.NEW_SYMBOL_USE) + AtomEdge(origin.id, test.id, AtomRelation.TEST_OF))
        val cluster = ChangeCluster("c", listOf(ClusterStep(origin.id, AtomRole.ORIGIN, 0)) +
            (callers.take(2) + other + callers.drop(2)).map { ClusterStep(it.id, AtomRole.PROPAGATION, 1) } +
            ClusterStep(test.id, AtomRole.TEST, 1), title = "Change make")
        val wide = ChangeMap(graph, listOf(cluster))
        val detail = ChangeMapPresentation(ChangeMapCard(wide, emptyList(), ReviewSummaries(buildChangeMapTree(wide, emptyList())), false, true)).detail("c")!!

        assertEquals(8, detail.rows.size)
        assertEquals(listOf("Origin", "Follows", "Tests"), detail.sections.map { it.title })
        assertEquals(listOf(true, false, true), detail.sections.map { it.open })
        val follows = detail.sections[1]
        assertEquals("The fan-out stands where its first member did", listOf(FanOut::class, SingleStep::class), follows.items.map { it::class })
        val fan = follows.items[0] as FanOut
        assertEquals("5 adapt to the changed signature of Foo.make", fan.title)
        assertEquals(callers.map { it.name }, fan.rows.map { it.name })
        assertEquals("+10 −10 · 5 declarations · 5 files", fan.stats)
        assertEquals("Other.call", (follows.items[1] as SingleStep).row.name)
        assertEquals(detail.rows.size + 3 + 1, detail.keys.size)
        assertTrue("section:PROPAGATION" in detail.keys && fan.key in detail.keys)
    }

    @Test fun `a collapsed step shows the first sentence of its summary and the rest when expanded`() {
        assertEquals("Adds retries to uploads.", gist("Adds retries to uploads. Keeps `x.y` intact."))
        assertEquals("Uses v1.2 of the API", gist("Uses v1.2 of the API"))
        assertTrue(gist("word ".repeat(100)).let { it.endsWith("…") && it.length <= 221 })

        val leaf = id("atom:${origin.id}")
        val model = presentation(ReviewSummaries(tree).summarize(mapOf(leaf to "Changes make. Callers now pass a size.")))
        val row = model.detail("k1")!!.rows.first()
        assertEquals("Changes make.", row.gist)
        assertEquals("Callers now pass a size.", row.more)
        val single = presentation(ReviewSummaries(tree).summarize(mapOf(leaf to "Changes make."))).detail("k1")!!.rows.first()
        assertEquals("Changes make.", single.gist)
        assertNull(single.more)
    }

    @Test fun `standalone changes are sectioned by what they are and a sole section starts open`() {
        val detail = presentation().detail(STANDALONE_ENTRY)!!
        assertEquals(listOf("Changes", "Docs"), detail.sections.map { it.title })
        assertEquals(listOf(listOf("Zed.zap"), listOf("README.md")), detail.sections.map { s -> s.rows.map { it.name } })
        val k2 = presentation().detail("k2")!!
        assertEquals(listOf("Origin", "Follows"), k2.sections.map { it.title })
        assertTrue(k2.sections.all { it.open && it.items.all { item -> item is SingleStep } })
    }

    @Test fun `an atom opens at its changed lines on the side each exists on`() {
        val trace = atomTrace(origin, files.single { it.path == origin.path })!!
        assertEquals("atom:${origin.id}", trace.identity)
        assertEquals(listOf(2, 7), trace.before)
        assertEquals(listOf(2, 7), trace.after)
        assertTrue(trace.lines.all { it.diff && it.marker != ' ' })
        val doc = atomTrace(readme, files.single { it.path == readme.path })!!
        assertEquals(listOf(1), doc.after)
        assertNull(atomTrace(lone, files.single { it.path == lone.path }.copy(patch = "")))
        assertEquals("src/Foo.kt:10-19", atomLocation(origin.copy(status = AtomStatus.REMOVED)))
    }

    @Test fun `word weights are given only when the tags cloud is on`() {
        val texts = tree.nodes.filter { it.children.isEmpty() }.associate { it.id to "Leaf ${it.label} changes the shared tag." }
        val summaries = ReviewSummaries(tree).summarize(texts + (id("cluster:k1") to "Make things from the shared tag."))
        val card = ChangeMapCard(map, files, summaries, running = false, canRun = true)

        val plain = ChangeMapPresentation(card).detail("k1")!!
        assertTrue(plain.weights.isEmpty())
        assertTrue(plain.rows.all { it.weights.isEmpty() })

        val cloud = ChangeMapPresentation(card, tagsCloud = true).detail("k1")!!
        assertTrue(cloud.weights.isNotEmpty())
        assertTrue(cloud.rows.all { it.weights.isNotEmpty() })
    }
}
