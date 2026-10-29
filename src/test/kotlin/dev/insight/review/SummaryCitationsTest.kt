package dev.insight.review

import dev.insight.review.SummarySource.Claim
import dev.insight.review.SummarySource.Rows
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import dev.insight.review.ui.SegmentRef
import dev.insight.review.ui.SummaryLinks
import dev.insight.review.ui.SummaryWord

class SummaryCitationsTest {
    @get:Rule val temp = TemporaryFolder()
    private data class Source(override val path: String) : ReviewSource {
        override val absolutePath get() = "/repo/$path"
        override val text get() = "line\n"
        override val fingerprint get() = "1"
        override val facts get() = emptyList<String>()
    }

    private fun part(text: String, vararg sources: SummarySource) = SummarySegment(text, sources.toList())
    private fun leaf(text: String) = Summary(listOf(part(text, Rows(1, 1))))

    @Test fun `shifted chunks and their groups are invalidated on refresh and disk restoration`() {
        val files = listOf(Source("big.kt"))
        fun tree(shift: Int) = buildSummaryTree(files, chunks = {
            List(20) { index ->
                val offset = if (index >= 7) shift else 0
                SourceChunk(index, (index * 10 + 1 + offset)..(index * 10 + 10 + offset), "content-$index")
            }
        })
        val original = tree(0)
        val written = ReviewSummaries(original).summarize(original.nodes.associate { node ->
            node.id to if (node.children.isEmpty()) Summary(listOf(part("Reads data.", Rows(node.lines!!.first, node.lines.last))))
                else Summary(listOf(part("Reads data.", Claim(0, 0))))
        }, reuseSingleChildren = false)
        val directory = temp.root.toPath().resolve("cache")
        fun session() = ReviewCacheSession(ReviewDiskCache(directory), "code", null).apply { load("scope") }
        session().save(written, ReviewTopics(files), ReviewSummaries(original))

        for (shift in listOf(-1, 1)) {
            val next = tree(shift)
            val retained = written.retainedIn(next)
            val restored = session().restoreSummaries(ReviewDiskCache.SUMMARIES, ReviewSummaries(next))
            for (result in listOf(retained, restored)) {
                for (node in next.nodes) {
                    if (node.kind == SummaryKind.CHUNK && node.lines!!.last <= 70) {
                        assertEquals(written.summary(node.id), result.summary(node.id))
                    } else {
                        assertNull("Shift $shift must invalidate ${node.identity}", result.summary(node.id))
                    }
                }
            }
        }
        // An unchanged snapshot still restores every citation, including the groups above the chunks.
        val restored = session().restoreSummaries(ReviewDiskCache.SUMMARIES, ReviewSummaries(original))
        assertTrue(restored.complete)
        original.nodes.forEach { assertEquals(written.summary(it.id), restored.summary(it.id)) }
    }

    @Test fun `segments round-trip through the cache form and plain text does not decode`() {
        val summary = Summary(listOf(part("Adds \"retries\"", Rows(3, 4)), part(" and "), part("caching.", Claim(1, 0))))
        assertEquals(summary, Summary.decode(summary.encode()))
        assertNull(Summary.decode("Adds retries and caching."))
        assertNull(Summary.decode("""[["  ",[]]]"""))
    }

    @Test fun `joining keeps words apart, trims the ends and drops empty segments`() {
        val summary = Summary.of(listOf(part("  Adds retries", Rows(1, 1)), part(""), part("and"), part("caching. ", Rows(2, 2))))!!
        assertEquals("Adds retries and caching.", summary.text)
        assertEquals(listOf(0..11, 12..15, 16..24), summary.ranges)
        assertEquals(1, summary.segmentAt(13))
        assertEquals(listOf(0, 2), summary.claims)
        assertEquals("""[{"cite":"1.1","text":"Adds retries"},{"cite":"1.3","text":"caching."}]""", summary.claimsJson(0).toString())
        assertNull(Summary.of(listOf(part(" "))))
    }

    @Test fun `joining repairs sentence seams without moving text between segments`() {
        val sentences = Summary.of(listOf(part("Splits segments.", Rows(1, 1)), part("Modified joins", Rows(2, 2)), part("; keeps", Rows(3, 3)),
            part("citations.", Rows(4, 4)), part(", matches", Rows(5, 5)), part("rows, e.g.", Rows(6, 6)), part(", ranges.", Rows(7, 7))))!!
        assertEquals("Splits segments. Modified joins; keeps citations, matches rows, e.g., ranges.", sentences.text)
        assertEquals(listOf("Splits segments.", " Modified joins", "; keeps", " citations", ", matches", " rows, e.g.", ", ranges."),
            sentences.segments.map { it.text })
        assertEquals((1..7).map { listOf(Rows(it, it)) }, sentences.segments.map { it.sources })
        // A decimal split across segments and text that already carries its space stay as written.
        assertEquals("Version 1.5 adds retries.", Summary.of(listOf(part("Version 1."), part("5 adds retries.")))!!.text)
        assertEquals("Retries. Logs.", Summary.of(listOf(part("Retries."), part(" Logs.")))!!.text)
    }

    @Test fun `a folder with one entry relays that entry's citations, also when kept across snapshots`() {
        val tree = buildSummaryTree(listOf(Source("lib/a.kt")))
        val file = tree.nodes.single { it.kind == SummaryKind.FILE }
        val lib = tree.nodes.single { it.path == "lib" }
        val written = ReviewSummaries(tree).summarize(mapOf(file.id to Summary(listOf(part("Reads", Rows(1, 1)), part(" and "), part("writes.", Rows(1, 1))))))
        assertEquals(listOf(listOf(Claim(0, 0)), emptyList(), listOf(Claim(0, 2))), written.summary(lib.id)!!.segments.map { it.sources })
        assertEquals(written.summary(lib.id), written.retainedIn(buildSummaryTree(listOf(Source("lib/a.kt")))).summary(lib.id))
    }

    @Test fun `the topic reading tree carries citations through groups to the files beneath`() {
        val files = (1..9).map { Source("src/f$it.kt") }
        val topics = ReviewTopics(files, files.associate { it.path to "Storage" })
        val tree = buildTopicSummaryTree(topics)
        val storage = tree.nodes.single { it.identity == "topic:storage" }
        val groups = storage.children.map(tree::get)
        assertEquals(listOf(SummaryKind.GROUP, SummaryKind.GROUP), groups.map { it.kind })
        val summaries = ReviewSummaries(tree)
            .summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to leaf("File ${it.path}.") })
            .summarize(groups.associate { group -> group.id to Summary(listOf(part("Group.", *group.children.indices.map { Claim(it, 0) }.toTypedArray()))) })
            .summarize(mapOf(storage.id to Summary(listOf(part("Stores data.", Claim(0, 0), Claim(1, 0))))))
        val reading = topicReadingSummaries(summaries, ReviewSummaries(buildSummaryTree(emptyList())))
        val readingStorage = reading.tree.nodes.single { it.identity == storage.identity }
        assertEquals(9, readingStorage.children.size)
        assertEquals((0 until 9).map { Claim(it, 0) }, reading.summary(readingStorage.id)!!.segments.single().sources)
        // A file reads its own summary and cites its lines.
        assertEquals(listOf(Rows(1, 1)), reading.summary(readingStorage.children.first())!!.segments.single().sources)
    }

    @Test fun `seeding a topic from the folder with the same files moves its citations to the matching children`() {
        val files = listOf(Source("lib/b.kt"), Source("lib/a.kt"))
        val folders = buildSummaryTree(files)
        val lib = folders.nodes.single { it.path == "lib" }
        val shared = ReviewSummaries(folders)
            .summarize(folders.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to leaf("File ${it.path}.") })
            .summarize(mapOf(lib.id to Summary(listOf(part("Only b.", Claim(lib.children.indexOfFirst { folders[it].path == "lib/b.kt" }, 0))))))
        val topicTree = buildTopicSummaryTree(ReviewTopics(files, files.associate { it.path to "Lib" }))
        val seeded = seedTopicSummaries(ReviewSummaries(topicTree), shared)
        val topic = topicTree.nodes.single { it.identity == "topic:lib" }
        val claim = seeded.summary(topic.id)!!.segments.single().sources.single() as Claim
        assertEquals("lib/b.kt", topicTree[topic.children[claim.child]].path)
    }

    @Test fun `seeding a topic preserves citations when children partition files differently`() {
        val files = listOf(Source("lib/sub/a.kt"), Source("lib/sub/b.kt"), Source("lib/c.kt"))
        val folders = buildSummaryTree(files)
        val sub = folders.nodes.single { it.path == "lib/sub" }
        val lib = folders.nodes.single { it.path == "lib" }
        val shared = ReviewSummaries(folders)
            .summarize(folders.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to leaf("File ${it.path}.") })
            .summarize(mapOf(sub.id to Summary(listOf(part("Sub a and b.", Claim(0, 0), Claim(1, 0))))))
            .summarize(mapOf(lib.id to Summary(listOf(
                part("Sub work", Claim(0, 0)),
                part(" and "),
                part("c work.", Claim(1, 0)),
            ))))
        val topicTree = buildTopicSummaryTree(ReviewTopics(files, mapOf(
            "lib/sub/a.kt" to "Lib",
            "lib/sub/b.kt" to "Lib > Sub",
            "lib/c.kt" to "Lib",
        )))
        val seeded = seedTopicSummaries(ReviewSummaries(topicTree), shared)
        val topic = topicTree.nodes.single { it.identity == "topic:lib" }
        val seededSummary = seeded.summary(topic.id)!!
        assertEquals(listOf(0, 2), seededSummary.claims)
        val subSources = seededSummary.segments[0].sources.filterIsInstance<Claim>()
        val citedPaths = subSources.flatMap { topicTree[topic.children[it.child]].files }.toSet()
        assertEquals(setOf("lib/sub/a.kt", "lib/sub/b.kt"), citedPaths)
        assertEquals(listOf("Sub work", "c work."), seededSummary.claimsJson(0).map { it.asJsonObject["text"].asString })

        val reading = topicReadingSummaries(seeded, shared)
        val readingTopic = reading.tree.nodes.single { it.identity == topic.identity }
        val links = SummaryLinks(reading)
        val subClaim = SegmentRef(readingTopic.id, 0)
        assertEquals(setOf("lib/sub/a.kt", "lib/sub/b.kt"), links.descendants(listOf(subClaim))
            .filter { reading.tree[it.id].kind == SummaryKind.FILE }.map { reading.tree[it.id].path }.toSet())
        fun dimmed(vararg paths: String): String {
            val reviewed = files.filter { it.path in paths }.mapTo(hashSetOf(), ReviewedFiles::key)
            val words = links.dimming(reviewed)[readingTopic.id].orEmpty()
            return SummaryWord.split(reading.text(readingTopic.id)!!)
                .filter { it.index in words }.joinToString(" ") { it.text }
        }
        assertEquals("c work", dimmed("lib/c.kt"))
        assertEquals("c work", dimmed("lib/c.kt", "lib/sub/a.kt"))
        assertEquals("Sub work", dimmed("lib/sub/a.kt", "lib/sub/b.kt"))
    }

    @Test fun `a single-file topic and its ancestors relay the seeded child's claims`() {
        val files = listOf(Source("a.kt"))
        val folders = buildSummaryTree(files)
        val file = folders.nodes.single { it.kind == SummaryKind.FILE }
        val shared = ReviewSummaries(folders).summarize(mapOf(file.id to leaf("Reads data.")))
        val topicTree = buildTopicSummaryTree(ReviewTopics(files, mapOf("a.kt" to "Storage > Reads")))
        val seeded = seedTopicSummaries(ReviewSummaries(topicTree), shared)
        assertTrue(seeded.complete)
        for (node in topicTree.nodes.filter { it.isTopicBranch }) {
            assertEquals(listOf(Claim(0, 0)), seeded.summary(node.id)!!.segments.single().sources)
        }
        val reading = topicReadingSummaries(seeded, shared)
        val root = SegmentRef(reading.tree.root.id, 0)
        val linked = SummaryLinks(reading).descendants(listOf(root))
        assertTrue(linked.any { reading.tree[it.id].kind == SummaryKind.FILE })
    }

    @Test fun `single-child topic seeding follows an existing child's summary and cache provenance`() {
        val files = listOf(Source("a.kt"))
        val folders = buildSummaryTree(files)
        val file = folders.nodes.single { it.kind == SummaryKind.FILE }
        val shared = ReviewSummaries(folders, mapOf(file.id to leaf("Reads data.")), setOf(file.id))
        val tree = buildTopicSummaryTree(ReviewTopics(files, mapOf("a.kt" to "Storage")))
        val topicFile = tree.nodes.single { it.kind == SummaryKind.FILE }
        val cached = seedTopicSummaries(ReviewSummaries(tree), shared)
        assertEquals(tree.nodes.map { it.id }.toSet(), cached.cachedIds)
        val existing = ReviewSummaries(tree, mapOf(topicFile.id to leaf("Reads and validates data.")))
        val seeded = seedTopicSummaries(existing, shared)
        assertEquals("Reads and validates data.", seeded.text(tree.root.id))
        assertTrue(seeded.cachedIds.isEmpty())
    }

    @Test fun `single-file topic chains preserve chunk citations through search echoes and parent dimming`() {
        val files = listOf(Source("a.kt"), Source("b.kt"))
        val chunks: (ReviewSource) -> List<SourceChunk> = { source ->
            if (source.path == "a.kt") listOf(SourceChunk(0, 1..1, "reads"), SourceChunk(1, 2..2, "writes")) else emptyList()
        }
        val folders = buildSummaryTree(files, chunks = chunks)
        val file = folders.nodes.single { it.path == "a.kt" && it.kind == SummaryKind.FILE }
        val other = folders.nodes.single { it.path == "b.kt" }
        val shared = ReviewSummaries(folders)
            .summarize(mapOf(
                file.children[0] to leaf("Reads data."),
                file.children[1] to Summary(listOf(part("Writes data.", Rows(2, 2)))),
                other.id to leaf("Draws controls."),
            ))
            .summarize(mapOf(file.id to Summary(listOf(part("Reads", Claim(0, 0)), part(" and "), part("writes.", Claim(1, 0))))))
            .summarize(mapOf(folders.root.id to Summary(listOf(part("Stores data.", Claim(0, 0), Claim(0, 2)),
                part(" Draws controls.", Claim(1, 0))))))
        val tree = buildTopicSummaryTree(ReviewTopics(files, mapOf("a.kt" to "Storage > Reads", "b.kt" to "UI")), chunks)
        val seeded = seedTopicSummaries(ReviewSummaries(tree), shared)
        assertTrue(seeded.complete)
        for (node in tree.nodes.filter { it.identity in setOf("topic:storage", "topic:storage > reads") }) {
            assertEquals(listOf(listOf(Claim(0, 0)), emptyList(), listOf(Claim(0, 2))),
                seeded.summary(node.id)!!.segments.map { it.sources })
        }

        val reading = topicReadingSummaries(seeded, shared)
        val links = SummaryLinks(reading)
        val readingFile = reading.tree.nodes.single { it.identity == file.identity }
        assertEquals(listOf(Rows(1, 1)), links.rows(SegmentRef(readingFile.id, 0)))
        assertEquals(listOf(Rows(2, 2)), links.rows(SegmentRef(readingFile.id, 2)))
        val storage = reading.tree.nodes.single { it.identity == "topic:storage" }
        val context = links.context(storage, 0)!!
        assertEquals(listOf(file.identity), links.search(storage.id, context).related.map { it.identity })
        assertTrue(SegmentRef(reading.tree.root.id, 0) in links.ancestors(listOf(SegmentRef(readingFile.id, 0))))
        assertTrue(links.dimming(emptySet())[reading.tree.root.id].isNullOrEmpty())
        val dimmed = links.dimming(setOf(ReviewedFiles.key(files[0])))[reading.tree.root.id].orEmpty()
        assertEquals(listOf("Stores", "data"), SummaryWord.split(reading.text(reading.tree.root.id)!!)
            .filter { it.index in dimmed }.map { it.text })
    }

    private fun linkedTree(): ReviewSummaries {
        val tree = buildSummaryTree(listOf("app/a.kt", "app/b.kt", "docs/c.kt", "docs/d.kt").map(::Source))
        val app = tree.nodes.single { it.path == "app" }
        val docs = tree.nodes.single { it.path == "docs" }
        val texts = tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to leaf("Reads data.") }.toMutableMap()
        texts[app.id] = Summary(listOf(part("Reads data", Claim(0, 0), Claim(1, 0)), part(" and "), part("validates it.", Claim(1, 0))))
        texts[docs.id] = Summary(listOf(part("Documents reads.", Claim(0, 0))))
        texts[tree.root.id] = Summary(listOf(part("Reads data", Claim(0, 0)), part(" with "), part("documentation.", Claim(1, 0))))
        return ReviewSummaries(tree, texts)
    }

    @Test fun `rewriting a child clears dependent claims through ancestors and keeps unrelated claims`() {
        val initial = linkedTree()
        val tree = initial.tree
        val file = tree.nodes.single { it.path == "app/a.kt" }
        val app = tree.nodes.single { it.path == "app" }
        val docs = tree.nodes.single { it.path == "docs" }
        val updated = initial.summarize(mapOf(file.id to leaf("Validates data before reading.")), reuseSingleChildren = false)
        assertEquals(initial.text(app.id), updated.text(app.id))
        assertTrue(updated.summary(app.id)!!.segments[0].sources.isEmpty())
        assertEquals(listOf(Claim(1, 0)), updated.summary(app.id)!!.segments[2].sources)
        assertTrue(updated.summary(tree.root.id)!!.segments[0].sources.isEmpty())
        assertEquals(listOf(Claim(1, 0)), updated.summary(tree.root.id)!!.segments[2].sources)
        assertEquals(initial.summary(docs.id), updated.summary(docs.id))
        assertTrue(SummaryLinks(updated).below(SegmentRef(app.id, 0)).isEmpty())
    }

    @Test fun `removing a child invalidates old citations before the replacement arrives`() {
        val initial = linkedTree()
        val tree = initial.tree
        val file = tree.nodes.single { it.path == "app/a.kt" }
        val app = tree.nodes.single { it.path == "app" }
        val start = initial.without(setOf(file.id))
        assertNull(start.summary(file.id))
        assertEquals(initial.text(app.id), start.text(app.id))
        assertTrue(start.summary(app.id)!!.segments[0].sources.isEmpty())
        assertTrue(start.summary(tree.root.id)!!.segments[0].sources.isEmpty())
        val updated = start.summarize(mapOf(file.id to leaf("Validates data before reading.")), reuseSingleChildren = false)
        assertTrue(updated.summary(app.id)!!.segments[0].sources.isEmpty())
    }

    @Test fun `fresh parent citations and unchanged child summaries stay linked`() {
        val initial = linkedTree()
        val tree = initial.tree
        val file = tree.nodes.single { it.path == "app/a.kt" }
        val app = tree.nodes.single { it.path == "app" }
        val unchanged = initial.summarize(mapOf(file.id to initial.summary(file.id)!!), reuseSingleChildren = false)
        assertEquals(initial.summary(app.id), unchanged.summary(app.id))
        val fresh = Summary(listOf(part("Validates reads.", Claim(0, 0))))
        val updated = initial.summarize(mapOf(file.id to leaf("Validates reads."), app.id to fresh), reuseSingleChildren = false)
        assertEquals(fresh, updated.summary(app.id))
        assertEquals(listOf(SegmentRef(file.id, 0)), SummaryLinks(updated).below(SegmentRef(app.id, 0)))
    }
}
