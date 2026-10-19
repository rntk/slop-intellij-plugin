package dev.insight.codereview

import dev.insight.diff.SourceChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChunkerTest {
    private val options = ChunkOptions()

    @Test fun `files up to singleChars are not chunked`() {
        assertEquals(emptyList<SourceChunk>(), chunkSource(""))
        assertEquals(emptyList<SourceChunk>(), chunkSource("val a = 1\n".repeat(options.singleChars / 10)))
        assertTrue(chunkSource("val a = 1\n".repeat(options.singleChars / 10 + 1)).isNotEmpty())
    }

    @Test fun `chunks cover every line once in order within the size limits`() {
        for (text in listOf(fixture(60), fixture(60).trimEnd('\n'), fixture(60) + "\n\n\n", fixture(25), noBlankLines(), shortLines(), flatBlock())) {
            val chunks = chunkSource(text)
            assertCovers(text, chunks)
            val lines = sourceLines(text)
            for (chunk in chunks) {
                assertTrue("$chunk is over maxChars", chars(lines, chunk.lines) <= options.maxChars)
                assertTrue("$chunk is over maxLines", chunk.lines.count() <= options.maxLines)
            }
            val last = chunks.last().lines
            assertTrue("last part $last is too small", chars(lines, last) >= options.minChars || last.count() >= options.minLines)
        }
    }

    @Test fun `parts start at top-level declarations with their documentation and annotations`() {
        val text = fixture(60)
        val lines = sourceLines(text)
        val chunks = chunkSource(text)
        assertTrue(chunks.size >= 4)
        for (chunk in chunks.drop(1)) {
            assertEquals("/**", lines[chunk.lines.first - 1])
            assertEquals("", lines[chunk.lines.first - 2])
        }
        val annotated = (1..60).joinToString("\n") { i ->
            "@Suppress(\"unused\")\n@JvmName(\"run$i\")\nfun run$i() {\n" + "    println(\"step $i of a long body\")\n".repeat(8) + "}\n"
        }
        val annotatedLines = sourceLines(annotated)
        val parts = chunkSource(annotated)
        assertTrue(parts.size >= 2)
        for (chunk in parts.drop(1)) assertTrue(annotatedLines[chunk.lines.first - 1].startsWith("@Suppress"))
    }

    @Test fun `boundary scores follow indentation and blank lines`() {
        val lines = listOf(
            "class A {",        // 0: top level (never a split: nothing precedes it)
            "    fun a() {",    // 1: inside, no blank before
            "    }",            // 2
            "",                 // 3
            "    fun b() = 1",  // 4: one unit, after blank
            "        x()",      // 5
            "",                 // 6
            "        y()",      // 7: deep, after blank
            "}",                // 8: closer
            "fun top() = 1",    // 9: after top-level closer
            "val v = 2",        // 10: top level
            "",                 // 11
            "// comment",       // 12: pulled-up target
            "@Ann",             // 13
            "fun c() = 3",      // 14
        )
        val scores = boundaryScores(lines)
        assertEquals(listOf(3, 0, 0, -1, 2, 0, -1, 1, 0, 4, 3, -1, 4, 0, 0), scores.toList())
    }

    @Test fun `chunking is deterministic and ignores CRLF`() {
        val text = fixture(60)
        assertEquals(chunkSource(text), chunkSource(text))
        assertEquals(chunkSource(text), chunkSource(text.replace("\n", "\r\n")))
    }

    @Test fun `an insertion near the top keeps later parts`() {
        val text = fixture(60)
        val before = chunkSource(text)
        val lines = sourceLines(text).toMutableList()
        lines.addAll(8, listOf("    // added one", "    // added two", "    val added = 3"))
        val after = chunkSource(lines.joinToString("\n", postfix = "\n"))
        assertTrue(before.size >= 4)
        val kept = after.map { it.hash }.toSet()
        val unchanged = before.drop(1).count { it.hash in kept }
        assertTrue("only $unchanged of ${before.size - 1} later parts kept", unchanged >= before.size - 2)
        assertFalse(before.first().hash in kept)
    }

    @Test fun `files without blank lines or with one huge line still split`() {
        val flat = noBlankLines()
        assertCovers(flat, chunkSource(flat))

        val giant = "x".repeat(25_000)
        assertEquals(listOf(1..1), chunkSource(giant).map { it.lines })

        val mixed = "val a = 1\n".repeat(50) + "y".repeat(25_000) + "\n" + "val b = 2\n".repeat(600)
        val chunks = chunkSource(mixed)
        assertCovers(mixed, chunks)
        assertTrue(chunks.any { it.lines == 51..51 })
    }

    @Test fun `many short lines split by line count`() {
        val text = shortLines()
        val chunks = chunkSource(text)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.lines.count() <= options.maxLines })
    }

    @Test fun `minified text is detected`() {
        assertTrue(isMinified("var a=1;".repeat(1_000)))
        assertTrue(isMinified(("x".repeat(400) + "\n").repeat(20)))
        assertFalse(isMinified(fixture(60)))
        assertFalse(isMinified(("x".repeat(400) + "\n").repeat(3)))
        assertFalse(isMinified(""))
    }

    private fun assertCovers(text: String, chunks: List<SourceChunk>) {
        val n = sourceLines(text.replace("\r\n", "\n")).size
        assertTrue(chunks.isNotEmpty())
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertEquals(1, chunks.first().lines.first)
        assertEquals(n, chunks.last().lines.last)
        chunks.zipWithNext { a, b -> assertEquals(a.lines.last + 1, b.lines.first) }
        assertTrue(chunks.all { !it.lines.isEmpty() })
    }

    private fun chars(lines: List<String>, range: IntRange) = range.sumOf { lines[it - 1].length + 1 }

    private fun fixture(count: Int): String = (1..count).joinToString("\n") { i ->
        """
        /**
         * Handles request kind $i and reports how much input it consumed.
         */
        @Suppress("unused")
        class Handler$i(private val limit: Int = ${i * 3}) {
            private val seen = mutableListOf<String>()

            fun handle(input: String): Int {
                val parts = input.split(',')
                var total = 0
                for (part in parts) {
                    if (part.isNotBlank() && seen.size < limit) {
                        seen += part.trim()
                        total += part.trim().length * $i
                    }
                }
                return total + ${i * 7}
            }

            fun reset() = seen.clear()
        }
        """.trimIndent() + "\n"
    }

    private fun noBlankLines(): String = (1..400).joinToString("\n", postfix = "\n") { i ->
        "fun f$i(x: Int): Int {\n    return x * $i + ${i * i}\n}"
    }

    private fun shortLines(): String = (1..3_000).joinToString("\n", postfix = "\n") { "a = $it" }

    // One indentation level, no blank lines: no boundary is better than another.
    private fun flatBlock(): String = (1..3_000).joinToString("\n", postfix = "\n") { "    step$it(value, $it)" }
}
