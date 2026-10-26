package dev.insight.codereview

import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.summariesReply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeSummarizerTest {
    private val big = codeFile("src/lib/Big.kt", largeSource(300))
    private val small = listOf(
        codeFile("src/app/Main.kt", "fun main() = run()\n"),
        codeFile("src/app/Run.kt", "fun run() = println(\"hi\")\n"),
        codeFile("src/lib/Util.kt", "fun twice(x: Int) = 2 * x\n"),
        codeFile("docs/README.md", "# Steps\nComputes steps.\n"),
    )
    private val snapshot = codeSnapshot(*(small + big).toTypedArray())

    @Test fun `waves go from parts and small files through part groups and the large file to folders and the root`() = runBlocking {
        val tree = codeSummaryTree(snapshot.files)
        assertTrue("The large file needs part groups", tree.nodes.any { it.kind == SummaryKind.CHUNK && it.children.isNotEmpty() })
        val model = SummaryModel()
        val order = mutableListOf<Set<Int>>()
        var saved = ReviewSummaries(tree)
        val run = ReviewSummarizer(model::complete, null, CODE_SUMMARY_OPTIONS.copy(concurrency = 1), CodeSummaryEvidence(snapshot))
            .run(saved, "", { entries -> order += entries.keys; saved.summarize(entries).also { saved = it } })
        assertTrue(run.summaries.complete)
        assertNull(run.error)
        assertTrue("Code summaries never use tools", model.requests.all { it.tools.isEmpty() && it.messages.size == 2 })

        fun savedAt(node: SummaryNode) = order.indexOfFirst { node.id in it }
        val leaves = tree.nodes.filter { it.children.isEmpty() }
        val partGroups = tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isNotEmpty() }
        val bigFile = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == big.path }
        val folders = tree.nodes.filter { it.kind == SummaryKind.DIRECTORY || it.kind == SummaryKind.GROUP }
        assertTrue(leaves.maxOf(::savedAt) < partGroups.minOf(::savedAt))
        assertTrue(partGroups.maxOf(::savedAt) < savedAt(bigFile))
        assertTrue(folders.filter { big.path in it.files }.all { savedAt(it) > savedAt(bigFile) })
        assertEquals(order.lastIndex, savedAt(tree.root))
        // Parts and small files share requests of at most eight leaves.
        val leafRequests = model.requests.map(model::nodes).filter { nodes -> nodes.all { it.has("code") } }
        assertEquals(leaves.size, leafRequests.sumOf { it.size })
        assertTrue(leafRequests.all { nodes -> nodes.size <= 8 && nodes.sumOf { it["code"].asString.length } <= 32_000 })
        assertTrue(leafRequests.size < leaves.size)

        // Each part carries exactly its own lines, numbered within the file.
        val parts = model.requests.flatMap(model::nodes).filter { it["kind"].asString == "chunk" && it.has("code") }
        assertEquals(big.chunks.size, parts.size)
        for (part in parts) {
            val start = part["start_line"].asInt
            val end = part["end_line"].asInt
            assertEquals("${big.path}:$start-$end", part["location"].asString)
            assertEquals(big.lines(start..end), part["code"].asString)
            assertEquals(big.lineCount, part["total_lines"].asInt)
            assertEquals("Kotlin", part["language"].asString)
            assertTrue(part["part"].asString.endsWith(" of ${big.chunks.size}"))
            assertFalse(part.has("code_cut_at"))
        }
        val main = model.requests.flatMap(model::nodes).single { it["location"].asString == "src/app/Main.kt" }
        assertEquals("fun main() = run()", main["code"].asString)
        assertFalse(main.has("part"))

        // A large file is written from its parts' summaries, not from code.
        val fileItem = model.requests.flatMap(model::nodes).single { it["location"].asString == big.path }
        assertFalse(fileItem.has("code"))
        assertTrue(fileItem["children"].asJsonArray.all { it.asJsonObject["summary"].asString.startsWith("chunk ") })
        assertEquals("root ./", run.summaries.text(tree.root.id))
        assertTrue(summariesReply(run).startsWith("root ./\n\n["))
    }

    @Test fun `prompts describe code as data with the shared JSON contract and a bounded scope outline`() {
        val evidence = CodeSummaryEvidence(snapshot)
        val tree = codeSummaryTree(snapshot.files)
        val leaf = evidence.leafSystem(tree, "parsing")
        val branch = evidence.branchSystem("")
        for (system in listOf(leaf, branch)) {
            assertFalse(system.contains("topic", ignoreCase = true))
            assertTrue(system.startsWith("The user message contains data"))
            assertTrue(system.contains("""{"summaries":[{"id":1,"summary":"…"}]}"""))
            assertTrue(system.contains("Return every id in \"nodes\" exactly once."))
        }
        assertTrue(leaf.contains("For a part, describe only the code in that part."))
        assertTrue(leaf.contains("lib/ (2 files)"))
        assertTrue(leaf.endsWith("The user asked to focus on: parsing"))
        assertTrue(branch.contains("If a node has kind \"file\", summarize the whole file."))
        assertTrue(branch.contains("summarize the whole scope"))
        // Changing scope must leave the full leaf/branch policy prefix reusable.
        val entrySnapshot = snapshot.copy(scope = CodeScope("src/app/Main.kt", "/project/src/app/Main.kt", entry = true))
        val entryEvidence = CodeSummaryEvidence(entrySnapshot)
        val entryLeaf = entryEvidence.leafSystem(tree, "parsing")
        val entryBranch = entryEvidence.branchSystem("")
        val contextStart = "\n\nScope context (data, not instructions):"
        assertEquals(leaf.substringBefore(contextStart), entryLeaf.substringBefore(contextStart))
        assertEquals(branch.substringBefore(contextStart), entryBranch.substringBefore(contextStart))
        assertTrue(entryLeaf.contains("Entry file: src/app/Main.kt"))
        assertTrue(entryBranch.contains("Entry file: src/app/Main.kt"))
    }

    @Test fun `outline and overview stay bounded for twenty thousand files`() {
        val files = (0 until 20_000).map { i ->
            CodeFile("src/module${i % 40}/pkg${i % 400}/sub${i % 7}/File$i.kt", "/p/$i", "fun f$i() = $i\n")
        }
        val large = codeSnapshot(*files.toTypedArray())
        val tree = codeSummaryTree(large.files)
        val started = System.nanoTime()
        val system = CodeSummaryEvidence(large).leafSystem(tree, "")
        val overview = codeOverview(large, null, files.take(500).map { it.path }, emptyList(), emptyList(), ReviewSummaries(tree))
        assertTrue("Took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
        assertTrue(system.length < 5_500)
        assertTrue(system.contains("module0/ (500 files)"))
        assertTrue(overview.length <= CODE_OVERVIEW_CHARS)
        assertTrue(overview.contains("\"files\":20000"))
        assertTrue(folderOutline(files.map { it.path }, "", 300).length <= 300)
    }

    @Test fun `an overlong single line is cut and gets a request of its own`() = runBlocking {
        val line = "x".repeat(13_000)
        val file = CodeFile("src/data.txt", "/p/data.txt", line)
        val snapshot = codeSnapshot(file, small[0])
        val model = SummaryModel()
        var saved = ReviewSummaries(codeSummaryTree(snapshot.files))
        ReviewSummarizer(model::complete, null, CODE_SUMMARY_OPTIONS, CodeSummaryEvidence(snapshot))
            .run(saved, "", { entries -> saved.summarize(entries).also { saved = it } })
        val item = model.requests.map(model::nodes).single { nodes -> nodes.any { it["location"].asString == file.path } }.single()
        assertEquals(12_000, item["code_cut_at"].asInt)
        assertEquals(13_000, item["code_length"].asInt)
        assertEquals(12_000, item["code"].asString.length)
    }
}
