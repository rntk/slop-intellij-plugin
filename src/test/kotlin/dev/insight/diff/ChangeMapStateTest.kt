package dev.insight.diff

import dev.insight.agent.ToolInputException
import dev.insight.diff.ChangeMapFixture.map
import dev.insight.diff.ChangeMapFixture.origin
import dev.insight.diff.ChangeMapFixture.user
import dev.insight.review.ReviewPipeline
import dev.insight.review.Summary
import dev.insight.review.isDone
import org.junit.Assert.*
import org.junit.Test

class ChangeMapStateTest {
    private fun state() = DiffReviewState().apply { update(ReviewSnapshot(ChangeMapFixture.files(), emptyList())) }

    private fun DiffReviewState.text(identity: String): String? =
        changeMapSummaries.tree.nodes.singleOrNull { it.identity == identity }?.let { changeMapSummaries.text(it.id) }

    private fun DiffReviewState.writeLeaves() = summarizeChangeMap(revision, changeMapSummaries.tree.nodes.filter { it.children.isEmpty() }
        .associate { it.id to Summary.plain("Leaf ${it.label}.") })

    @Test fun `a new map keeps the summaries of unchanged atoms and the pipeline is done once every summary is written`() {
        val state = state()
        assertNull(state.changeMap)
        assertEquals(0, state.changeMapSummaries.total)
        assertFalse(state.isDone(ReviewPipeline.CHANGE_MAP))

        state.setChangeMap(state.revision, state.snapshot.files, map)
        assertSame(map, state.changeMap)
        assertFalse(state.isDone(ReviewPipeline.CHANGE_MAP))
        state.writeLeaves()
        assertEquals("Leaf Foo.make.", state.text("atom:${origin.id}"))

        val edited = map.copy(graph = map.graph.copy(atoms = map.graph.atoms.map { if (it.id == user.id) it.copy(fingerprint = "edited") else it }))
        state.setChangeMap(state.revision, state.snapshot.files, edited)
        assertEquals("Leaf Foo.make.", state.text("atom:${origin.id}"))
        assertNull(state.text("atom:${user.id}"))

        val tree = state.changeMapSummaries.tree
        state.summarizeChangeMap(state.revision, state.changeMapSummaries.missing().associateWith { Summary.plain("Node ${tree[it].label}.") }
            .filterKeys { tree[it].children.isEmpty() })
        state.summarizeChangeMap(state.revision, state.changeMapSummaries.missing().filter { tree[it].identity != "change-map-root" }
            .associateWith { Summary.plain("Branch.") })
        state.summarizeChangeMap(state.revision, mapOf(tree.root.id to Summary.plain("All.")))
        assertTrue(state.changeMapSummaries.complete)
        assertTrue(state.isDone(ReviewPipeline.CHANGE_MAP))

        state.clearChangeMapSummaries()
        assertEquals(0, state.changeMapSummaries.count)
        assertSame(edited, state.changeMap)
    }

    @Test fun `a changed snapshot drops the map and the next map of it keeps what is unchanged`() {
        val state = state()
        state.setChangeMap(state.revision, state.snapshot.files, map)
        state.writeLeaves()
        val files = state.snapshot.files.map { if (it.path == user.path) it.copy(fingerprint = "v2", patch = it.patch + " context 15\n") else it }
        state.update(ReviewSnapshot(files, emptyList()))
        assertNull(state.changeMap)
        assertEquals(0, state.changeMapSummaries.total)
        assertFalse(state.isDone(ReviewPipeline.CHANGE_MAP))

        val next = map.copy(graph = map.graph.copy(atoms = map.graph.atoms.map { if (it.id == user.id) it.copy(fingerprint = "v2") else it }))
        state.setChangeMap(state.revision, state.snapshot.files, next)
        assertEquals("Leaf Foo.make.", state.text("atom:${origin.id}"))
        assertNull(state.text("atom:${user.id}"))
    }

    @Test fun `a map or summaries of an older revision or another file list are rejected`() {
        val state = state()
        val revision = state.revision
        val files = state.snapshot.files
        assertThrows(ToolInputException::class.java) { state.summarizeChangeMap(revision, emptyMap()) }
        state.update(ReviewSnapshot(files.map { it.copy(fingerprint = "moved") }, emptyList()))
        assertTrue(assertThrows(ToolInputException::class.java) { state.setChangeMap(revision, files, map) }.message!!.startsWith("The diff changed."))
        assertThrows(ToolInputException::class.java) { state.setChangeMap(state.revision, files, map) }
        assertNull(state.changeMap)
        state.setChangeMap(state.revision, state.snapshot.files, map)
        assertThrows(ToolInputException::class.java) { state.summarizeChangeMap(revision, mapOf(0 to Summary.plain("Stale."))) }
        state.refreshing = true
        assertThrows(ToolInputException::class.java) { state.setChangeMap(state.revision, state.snapshot.files, map) }
    }

    @Test fun `clearing the canvas hides the card and keeps the map`() {
        val state = state()
        state.showChangeMap()
        state.setChangeMap(state.revision, state.snapshot.files, map)
        state.clearNotes()
        assertFalse(state.changeMapVisible)
        assertSame(map, state.changeMap)
    }
}
