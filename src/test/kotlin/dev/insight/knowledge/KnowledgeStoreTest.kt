package dev.insight.knowledge

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class KnowledgeStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val rich = Claim(
        "c-1", "Unicode ✓ \"quoted\"\nline two", ClaimLevel.FLOW, ClaimOrigin.HUMAN, "src/main",
        listOf(Anchor(AnchorKind.CALL_EDGE, "a.B.f->c.D.g", "fp"), Anchor(AnchorKind.DIRECTORY, "src/main")),
        listOf(Citation("src/A.kt", 3, 9, "see"), Citation("README.md")),
        linkedSetOf("detail", "volatile"), 0.7, ClaimStatus.Live, "flows/diff-walkthrough.md",
    )
    private val plain = claim("c-2")
    private val statuses = listOf(
        ClaimStatus.Live,
        ClaimStatus.Suspect("moved"),
        ClaimStatus.Retired("gone", RetireEvidence.AnchorsGone(listOf("a", "b"))),
        ClaimStatus.Retired("wrong", RetireEvidence.Contradicted(0.91, "says otherwise")),
        ClaimStatus.Retired("meh", RetireEvidence.ByUser("note")),
        ClaimStatus.Superseded(listOf("x", "y")),
    )
    private val ops = listOf(
        KnowledgeOp.Add(rich),
        KnowledgeOp.Rewrite("c-1", plain),
        KnowledgeOp.Merge(listOf("a", "b"), rich),
        KnowledgeOp.Split("c-1", listOf(rich, plain)),
        KnowledgeOp.Retire("a", "r", RetireEvidence.AnchorsGone(listOf("p"))),
        KnowledgeOp.Retire("a", "r", RetireEvidence.Contradicted(1.0, "e")),
        KnowledgeOp.Retire("a", "r", RetireEvidence.ByUser("n")),
        KnowledgeOp.MarkSuspect("a", "r"),
        KnowledgeOp.Confirm("a", rich.anchors),
        KnowledgeOp.Reanchor("a", emptyList()),
        KnowledgeOp.Contradict("a", "b", "r"),
        KnowledgeOp.Restore("a"),
    ) + statuses.map { KnowledgeOp.Add(plain.copy(status = it)) }

    private fun entries() = ops.mapIndexed { i, op ->
        LedgerEntry(i + 1L, op, "worker:src", 1_700_000_000_000 + i,
            if (i % 2 == 0) Verdict.Accepted(listOf("n=$i")) else Verdict.Rejected("because $i"))
    }

    @Test fun `every op status evidence and verdict round trips`() {
        entries().forEach { entry ->
            val line = KnowledgeJson.encodeEntry(entry)
            assertTrue(!line.contains('\n'))
            assertEquals(entry, KnowledgeJson.decodeEntry(line))
        }
        val json = JsonParser.parseString(KnowledgeJson.encodeEntry(entries()[3])).asJsonObject
        assertEquals("split", json["op"].asJsonObject["type"].asString)
    }

    @Test fun `file store persists entries as jsonl`() {
        val dir = temp.root.toPath().resolve("nested/knowledge")
        val store = FileKnowledgeStore(dir)
        assertEquals(emptyList<LedgerEntry>(), store.load())
        entries().forEach(store::append)
        assertEquals(entries().size, Files.readAllLines(dir.resolve("ledger.jsonl")).size)
        assertEquals(entries(), FileKnowledgeStore(dir).load())
    }

    @Test fun `corrupt trailing line is dropped and later appends stay readable`() {
        val dir = temp.root.toPath()
        val store = FileKnowledgeStore(dir)
        val (first, second, third) = entries()
        store.append(first)
        store.append(second)
        Files.writeString(store.file, Files.readString(store.file) + "{\"seq\": 3, \"op\": {\"ty")
        assertEquals(listOf(first, second), store.load())
        store.append(third)
        assertEquals(listOf(first, second, third), FileKnowledgeStore(dir).load())
    }

    @Test fun `trailing line without newline is appended after a separator`() {
        val store = FileKnowledgeStore(temp.root.toPath())
        val (first, second) = entries()
        Files.writeString(store.file, KnowledgeJson.encodeEntry(first))
        store.append(second)
        assertEquals(listOf(first, second), store.load())
    }

    @Test fun `corrupt middle line throws`() {
        val store = FileKnowledgeStore(temp.root.toPath())
        val (first, second) = entries()
        Files.writeString(store.file, KnowledgeJson.encodeEntry(first) + "\nnot json\n" + KnowledgeJson.encodeEntry(second) + "\n")
        assertThrows(KnowledgeStoreException::class.java) { store.load() }
    }

    @Test fun `default dir`() {
        assertEquals(Path.of("/p/.insight/knowledge"), defaultKnowledgeDir(Path.of("/p")))
        val memory = InMemoryKnowledgeStore()
        entries().forEach(memory::append)
        assertEquals(entries(), memory.load())
    }
}
