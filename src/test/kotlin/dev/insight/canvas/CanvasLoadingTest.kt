package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLoadingTest {
    private fun card(id: String, parentId: String? = CANVAS_ROOT, title: String = id): CanvasCard =
        CanvasCard(id, parentId, CardKind.SELECTION, CardAnchor(0, 1, 0), null, "$title.kt", 0, "code($id)")

    @Test
    fun `default thresholds are generous for normal graphs and safely ordered`() {
        assertTrue(CANVAS_LOAD_CHUNK >= 50)
        assertTrue(CANVAS_ACTIVE_EDITOR_LIMIT > CANVAS_LOAD_CHUNK)
        assertTrue(CANVAS_ACTIVE_EDITOR_TARGET < CANVAS_ACTIVE_EDITOR_LIMIT)
    }

    @Test
    fun `initial loaded subset takes everything on small graphs and rooted BFS chunk on large ones`() {
        val small = listOf(canvasRootCard("Root.kt"), card("a"), card("b"), card("a1", "a"))
        assertEquals(setOf(CANVAS_ROOT, "a", "b", "a1"), selectInitialLoadedCards(small, limit = 10))

        // Build a 500-card tree: 10 root children, each with 9 children, each with ~4 grandchildren.
        val large = mutableListOf(canvasRootCard("Root.kt"))
        for (i in 1..10) {
            val top = "t$i"
            large += card(top, CANVAS_ROOT)
            for (j in 1..9) {
                val mid = "m${i}_$j"
                large += card(mid, top)
                for (k in 1..5) {
                    if (large.size <= 500) large += card("l${i}_${j}_$k", mid)
                }
            }
        }
        val selected = selectInitialLoadedCards(large, limit = 25)
        assertEquals(26, selected.size)
        assertTrue(CANVAS_ROOT in selected)
        // Root's 10 direct children (level 1) must all load before level 2.
        for (i in 1..10) assertTrue("t$i" in selected)
        // Ancestor-closed invariant.
        val byId = large.associateBy { it.id }
        for (id in selected) {
            val parent = byId.getValue(id).parentId
            if (parent != null) assertTrue("$id must have loaded parent $parent", parent in selected)
        }
    }

    @Test
    fun `next loaded cards expand globally and per branch without duplicates`() {
        val cards = mutableListOf(canvasRootCard("Root.kt"))
        cards += listOf(card("a"), card("b"))
        for (i in 1..6) cards += card("a$i", "a")
        for (i in 1..4) cards += card("b$i", "b")
        cards += listOf(card("a1x", "a1"), card("b1x", "b1"))

        val loaded = selectInitialLoadedCards(cards, limit = 2)
        assertEquals(setOf(CANVAS_ROOT, "a", "b"), loaded)

        // Branch-specific load only expands under "b".
        val branchBatch = selectNextLoadedCards(cards, loaded, limit = 3, branchParentId = "b")
        assertEquals(listOf("b1", "b2", "b3"), branchBatch.map { it.id })
        loaded.addAll(branchBatch.map { it.id })
        assertFalse("a1" in loaded)

        // Global chunks finish all remaining nodes in parent-before-child order with no duplicates.
        val seen = loaded.toMutableSet()
        while (true) {
            val batch = selectNextLoadedCards(cards, loaded, limit = 3)
            if (batch.isEmpty()) break
            for (c in batch) {
                assertTrue("${c.id} parent ${c.parentId} must already be loaded", c.parentId in loaded)
                assertTrue("Duplicate card ${c.id}", seen.add(c.id))
                loaded += c.id
            }
        }
        assertEquals(cards.map { it.id }.toSet(), loaded)
    }

    @Test
    fun `chain to load resolves unloaded ancestors root-first and skips loaded or missing targets`() {
        val cards = listOf(
            canvasRootCard("Root.kt"),
            card("a"),
            card("a1", "a"),
            card("a2", "a1"),
            card("orphan", "missing"),
        )
        val loaded = setOf(CANVAS_ROOT, "a")
        assertEquals(listOf("a1", "a2"), selectChainToLoad(cards, loaded, "a2").map { it.id })
        assertTrue(selectChainToLoad(cards, loaded, "a").isEmpty())
        assertTrue(selectChainToLoad(cards, loaded, "unknown").isEmpty())
        assertTrue(selectChainToLoad(cards, loaded, "orphan").isEmpty())
    }

    @Test
    fun `deferred branches partition unloaded subtrees and pending files under deepest loaded parents`() {
        val cards = listOf(
            canvasRootCard("Root.kt"),
            card("a"),
            card("b"),
            card("a1", "a"),
            card("a2", "a1"),
            card("b1", "b"),
        )
        val loaded = setOf(CANVAS_ROOT, "a", "b")
        val branches = deferredBranches(
            cards,
            loaded,
            pendingByParent = mapOf("b" to 3, CANVAS_ROOT to 2),
            directoryPending = 0,
        )
        assertEquals(
            listOf(
                CanvasDeferredBranch(CANVAS_ROOT, deferredCards = 0, pendingFiles = 2, isDirectoryFiles = false),
                CanvasDeferredBranch("a", deferredCards = 2, pendingFiles = 0, isDirectoryFiles = false),
                CanvasDeferredBranch("b", deferredCards = 1, pendingFiles = 3, isDirectoryFiles = false),
            ),
            branches,
        )
        assertEquals("+2 more files", branches[0].title())
        assertEquals("+2 more cards", branches[1].title())
        assertEquals("+1 more card, +3 more files", branches[2].title())
        assertEquals("Load all 4", branches[2].actionLabel(chunkSize = 10))
        assertEquals("Load next 3", branches[2].actionLabel(chunkSize = 3))

        val dirOnly = deferredBranches(listOf(canvasRootCard("dir")), setOf(CANVAS_ROOT), directoryPending = 80)
        assertEquals(listOf(CanvasDeferredBranch(CANVAS_ROOT, 0, 80, isDirectoryFiles = true)), dirOnly)
        assertEquals("+80 more files", dirOnly.single().title())
        assertEquals("Load next 60", dirOnly.single().actionLabel(60))
    }

    @Test
    fun `editor planner skips hibernation under limit, protects pinned cards, uses hysteresis, and caps zoom-out`() {
        val bounds = (0 until 10).map { i ->
            CanvasCardBounds("c$i", x = i * 500, y = 0, width = 300, height = 200)
        }
        val viewport = CanvasViewportRect(x = 0, y = 0, width = 600, height = 400)

        // Under limit: everything materializes, nothing hibernates.
        val smallPlan = selectActiveEditorCards(bounds.take(5), viewport, materialized = emptySet(), pinned = setOf("c0"), limit = 5, target = 3)
        assertEquals(setOf("c0", "c1", "c2", "c3", "c4"), smallPlan.materialize)
        assertTrue(smallPlan.hibernate.isEmpty())

        // Hysteresis: currently 4 active (<= limit 6), 1 more visible/prefetch card -> total 5 <= 6 -> no eviction.
        val hysteresisPlan = selectActiveEditorCards(
            bounds,
            viewport,
            materialized = setOf("c0", "c7", "c8", "c9"),
            pinned = setOf("c0"),
            limit = 6,
            target = 4,
        )
        assertTrue("c1" in hysteresisPlan.materialize)
        assertTrue(hysteresisPlan.hibernate.isEmpty())

        // Over limit: prunes farthest off-screen cards down to target while keeping pinned far card c9.
        val prunePlan = selectActiveEditorCards(
            bounds,
            viewport,
            materialized = setOf("c0", "c4", "c5", "c6", "c7", "c8", "c9"),
            pinned = setOf("c0", "c9"),
            limit = 6,
            target = 4,
        )
        assertTrue("c1" in prunePlan.materialize)
        assertFalse("c9" in prunePlan.hibernate)
        assertFalse("c0" in prunePlan.hibernate)
        assertTrue("c8" in prunePlan.hibernate)

        // Extreme zoom-out where all 10 cards are visible: active editors stay capped at maxOf(limit=4, pinned=1) = 4.
        val giantViewport = CanvasViewportRect(-1000, -1000, 20_000, 10_000)
        val zoomOutPlan = selectActiveEditorCards(
            bounds,
            giantViewport,
            materialized = bounds.mapTo(hashSetOf()) { it.id },
            pinned = setOf("c9"),
            limit = 4,
            target = 3,
        )
        val finalActive = (bounds.map { it.id }.toSet() - zoomOutPlan.hibernate) + zoomOutPlan.materialize
        assertEquals(4, finalActive.size)
        assertTrue("c9" in finalActive)
    }

    @Test
    fun `extendFileConnectionForest connects batch files to existing loaded cards before standalone seeds`() {
        val connections = listOf(
            FileConnection("Existing.kt", "Linked.kt", 12, 0),
            FileConnection("Linked.kt", "Deep.kt", 25, 0),
        )
        val added = extendFileConnectionForest(
            existing = listOf("Existing.kt"),
            batch = listOf("Deep.kt", "Linked.kt", "Standalone.kt"),
            connections = connections,
        )
        assertEquals(
            listOf(
                ConnectedFile("Linked.kt", "Existing.kt", 12),
                ConnectedFile("Deep.kt", "Linked.kt", 25),
                ConnectedFile("Standalone.kt", null, 0),
            ),
            added,
        )
    }

    @Test
    fun `estimateBaseSourceHeight scales with lines and stays bounded`() {
        assertEquals(48, estimateBaseSourceHeight("one line"))
        assertTrue(estimateBaseSourceHeight((1..20).joinToString("\n") { "line $it" }) > 48)
        assertEquals(CARD_SOURCE_MAX_HEIGHT, estimateBaseSourceHeight("x\n".repeat(200)))
    }

    @Test
    fun `deferredBranches handles deep chains linearly and skips cycles or orphans`() {
        val cards = mutableListOf(canvasRootCard("Root.kt"))
        var parent = CANVAS_ROOT
        for (i in 1..20_000) {
            val id = "n$i"
            cards += card(id, parent)
            parent = id
        }
        cards += card("cycA", "cycB")
        cards += card("cycB", "cycA")
        cards += card("orphan", "missing")

        val branches = deferredBranches(cards, setOf(CANVAS_ROOT, "n1"))
        assertEquals(listOf(CanvasDeferredBranch("n1", deferredCards = 19_999)), branches)
    }

    @Test
    fun `reconcileBatchParents keeps valid parents and reparents orphaned roots`() {
        val anchor = CardAnchor(4, 9, 2)
        val intact = card("b1", "openParent").copy(anchor = anchor)
        val orphaned = card("b2", "closedParent").copy(anchor = anchor)
        val batchChild = card("b3", "b2").copy(anchor = anchor)

        val reconciled = reconcileBatchParents(setOf(CANVAS_ROOT, "openParent"), listOf(intact, orphaned, batchChild))
        assertEquals(intact, reconciled[0])
        assertEquals(CANVAS_ROOT, reconciled[1].parentId)
        assertEquals(CardAnchor(0, 0, 0), reconciled[1].anchor)
        assertEquals("b2", reconciled[2].parentId)
        assertEquals(anchor, reconciled[2].anchor)
    }

    @Test
    fun `formatPendingLoadNotice formats retryable errors and unavailable files`() {
        assertNull(formatPendingLoadNotice(PendingLoadResult(emptyList())))
        assertEquals(
            "Could not load more: Refresh required",
            formatPendingLoadNotice(PendingLoadResult(emptyList(), error = "Refresh required")),
        )
        assertEquals(
            "Not shown: a.txt, b.txt",
            formatPendingLoadNotice(PendingLoadResult(emptyList(), unavailable = listOf("a.txt", "b.txt"))),
        )
        assertEquals(
            "Could not load more: Refresh required · Unavailable: a.txt",
            formatPendingLoadNotice(PendingLoadResult(emptyList(), unavailable = listOf("a.txt"), error = "Refresh required")),
        )
    }

    @Test
    fun `sourceLineOffset handles nonzero firstLine, empty lines, and out-of-range inputs`() {
        assertNull(sourceLineOffset("", firstLine = 0, line = 0))
        assertNull(sourceLineOffset("alpha\nbeta", firstLine = 5, line = 4))
        assertNull(sourceLineOffset("alpha\nbeta", firstLine = 5, line = -1))
        assertEquals(0, sourceLineOffset("alpha\nbeta", firstLine = 5, line = 5))
        assertEquals(6, sourceLineOffset("alpha\nbeta", firstLine = 5, line = 6))
        assertNull(sourceLineOffset("alpha\nbeta", firstLine = 5, line = 7))

        val withEmpty = "\n\nmid\n"
        assertEquals(0, sourceLineOffset(withEmpty, firstLine = 10, line = 10))
        assertEquals(1, sourceLineOffset(withEmpty, firstLine = 10, line = 11))
        assertEquals(2, sourceLineOffset(withEmpty, firstLine = 10, line = 12))
        assertEquals(6, sourceLineOffset(withEmpty, firstLine = 10, line = 13))
        assertNull(sourceLineOffset(withEmpty, firstLine = 10, line = 14))
    }
}
