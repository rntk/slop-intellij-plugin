package dev.insight.codereview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CodeReviewLoaderTest : BasePlatformTestCase() {
    fun `test loads text files under a subdirectory and skips binary large and minified files`() {
        myFixture.addFileToProject("scope/a.kt", "fun a() = 1\n")
        myFixture.addFileToProject("scope/inner/b.txt", "hello\nworld\n")
        myFixture.addFileToProject("other/c.kt", "fun c() = 3\n")
        myFixture.addFileToProject("scope/Big.kt", bigText())
        myFixture.addFileToProject("scope/min.txt", "var a=1;".repeat(1_000))
        // The JavaScript plugin reports minified scripts as generated sources.
        myFixture.addFileToProject("scope/app.min.js", "var a=1;".repeat(1_000))
        binary("scope/logo.png")

        val scope = scope("scope")
        assertEquals("scope", scope.path)
        val snapshot = load(scope)
        assertEquals(listOf("scope/Big.kt", "scope/a.kt", "scope/inner/b.txt"), snapshot.files.map { it.path })
        assertEquals(setOf(SkippedFile("scope/app.min.js", SkipReason.GENERATED), SkippedFile("scope/logo.png", SkipReason.BINARY),
            SkippedFile("scope/min.txt", SkipReason.MINIFIED)),
            snapshot.skipped.toSet())
        assertFalse(snapshot.truncated)

        val small = snapshot.byPath.getValue("scope/a.kt")
        assertEquals("fun a() = 1\n", small.text)
        assertTrue(small.absolutePath.endsWith("/scope/a.kt"))
        assertEquals(emptyList<Any>(), small.chunks)
        assertEquals(listOf("1 line"), small.facts)
        assertEquals("world", snapshot.byPath.getValue("scope/inner/b.txt").lines(2..2))

        val big = snapshot.byPath.getValue("scope/Big.kt")
        assertTrue(big.chunks.size >= 3)
        assertEquals(1, big.chunks.first().lines.first)
        assertEquals(big.lineCount, big.chunks.last().lines.last)
        assertEquals(listOf("${String.format(java.util.Locale.US, "%,d", big.lineCount)} lines", "${big.chunks.size} parts"), big.facts)
        assertEquals(1, snapshot.chunkedFiles)
        assertEquals(big.chunks.size, snapshot.partCount)

        val tooLarge = load(scope, limits = CodeLimits(maxFileChars = 5_000))
        assertTrue(SkippedFile("scope/Big.kt", SkipReason.TOO_LARGE) in tooLarge.skipped)
    }

    fun `test reload reuses unchanged files and rereads changed ones`() {
        val a = myFixture.addFileToProject("reuse/a.kt", "fun a() = 1\n").virtualFile
        myFixture.addFileToProject("reuse/b.kt", "fun b() = 2\n")
        val scope = scope("reuse")
        val first = load(scope)
        val second = load(scope, first)
        assertSame(first.byPath.getValue("reuse/a.kt"), second.byPath.getValue("reuse/a.kt"))
        assertSame(first.byPath.getValue("reuse/b.kt"), second.byPath.getValue("reuse/b.kt"))

        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(a, "fun a() = 10\n") }
        val third = load(scope, second)
        assertEquals("fun a() = 10\n", third.byPath.getValue("reuse/a.kt").text)
        assertFalse(second.byPath.getValue("reuse/a.kt") == third.byPath.getValue("reuse/a.kt"))
        assertSame(second.byPath.getValue("reuse/b.kt"), third.byPath.getValue("reuse/b.kt"))

        // Other chunk options read the files again.
        val rechunked = load(scope, third, CodeLimits(chunk = ChunkOptions(singleChars = 5)))
        assertNotSame(third.byPath.getValue("reuse/b.kt"), rechunked.byPath.getValue("reuse/b.kt"))
    }

    fun `test limits truncate in path order`() {
        for (name in listOf("c", "a", "b", "d")) myFixture.addFileToProject("limited/$name.kt", "fun $name() = 1\n")
        val scope = scope("limited")
        val byCount = load(scope, limits = CodeLimits(maxFiles = 2))
        assertEquals(listOf("limited/a.kt", "limited/b.kt"), byCount.files.map { it.path })
        assertTrue(byCount.truncated)
        assertEquals(2, byCount.overLimit)
        assertEquals(listOf("limited/c.kt", "limited/d.kt"), byCount.skipped.filter { it.reason == SkipReason.LIMIT }.map { it.path })

        val byChars = load(scope, limits = CodeLimits(maxTotalChars = "fun a() = 1\n".length * 3L))
        assertEquals(3, byChars.files.size)
        assertTrue(byChars.truncated)
        assertEquals(1, byChars.overLimit)
    }

    fun `test excluded folders are skipped and cannot be a scope`() {
        myFixture.addFileToProject("withExcluded/kept.kt", "fun kept() = 1\n")
        myFixture.addFileToProject("withExcluded/out/gone.kt", "fun gone() = 1\n")
        val out = myFixture.findFileInTempDir("withExcluded/out")
        PsiTestUtil.addExcludedRoot(module, out)
        try {
            assertEquals(listOf("withExcluded/kept.kt"), load(scope("withExcluded")).files.map { it.path })
            assertThrows(IllegalArgumentException::class.java) { scope("withExcluded/out") }
        } finally {
            PsiTestUtil.removeExcludedRoot(module, out)
        }
    }

    fun `test scope validation`() {
        myFixture.addFileToProject("valid/a.kt", "fun a() = 1\n")
        val directory = myFixture.findFileInTempDir("valid")
        assertEquals(scope("valid"), ReadAction.compute<CodeScope, Throwable> { codeScope(project, directory) })
        assertEquals(scope("valid"), scope(directory.path))
        assertEquals("", scope("").path)
        assertEquals("", scope(".").path)
        assertThrows(IllegalArgumentException::class.java) { scope("missing") }
        assertThrows(IllegalArgumentException::class.java) { scope("valid/a.kt") }
        assertThrows(IllegalArgumentException::class.java) { scope("valid/../valid") }
    }

    private fun scope(path: String): CodeScope = ReadAction.compute<CodeScope, Throwable> { codeScope(project, path) }

    private fun binary(path: String) {
        val file = myFixture.tempDirFixture.createFile(path)
        WriteAction.runAndWait<Throwable> {
            file.setBinaryContent(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0, 1, 2, 3))
        }
    }

    private fun bigText(): String = (1..80).joinToString("\n") { i ->
        "/** Handles kind $i. */\nclass Handler$i {\n" +
            (1..8).joinToString("") { "    fun step$it(value: Int): Int = value * $i + $it // keeps the body long enough\n" } + "}\n"
    }

    private fun load(scope: CodeScope, previous: CodeSnapshot? = null, limits: CodeLimits = CodeLimits()): CodeSnapshot {
        val coroutines = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var result: CodeSnapshot? = null
        var failure: Throwable? = null
        val job = coroutines.launch {
            try { result = CodeReviewLoader(project).load(scope, previous, limits) } catch (e: Throwable) { failure = e }
        }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Loading should finish", { job.isCompleted }, 20)
            failure?.let { throw it }
            return result!!
        } finally { coroutines.cancel() }
    }
}
