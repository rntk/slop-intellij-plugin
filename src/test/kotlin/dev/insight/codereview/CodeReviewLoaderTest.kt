package dev.insight.codereview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
    fun `test generated review cache is pruned from scopes before it is read`() {
        myFixture.addFileToProject("scope/a.kt", "fun a() = 1")
        myFixture.addFileToProject("scope/.insight/review-cache/result.json", "{\"summary\":\"generated\"}")
        val snapshot = load(scope("scope"))
        assertEquals(listOf("scope/a.kt"), snapshot.files.map { it.path })
        assertEquals(1, snapshot.ignoredCount)
    }

    fun `test loads text files under a subdirectory and skips binary large and minified files`() {
        myFixture.addFileToProject("scope/a.kt", "fun a() = 1\n")
        myFixture.addFileToProject("scope/inner/b.txt", "hello\nworld\n")
        myFixture.addFileToProject("other/c.kt", "fun c() = 3\n")
        myFixture.addFileToProject("scope/Big.kt", bigText())
        myFixture.addFileToProject("scope/min.txt", "var a=1;".repeat(1_000))
        // The JavaScript plugin reports minified scripts as generated sources.
        myFixture.addFileToProject("scope/app.min.js", "var a=1;".repeat(1_000))
        binary()

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

    fun `test discarded unsaved edits are replaced with disk text`() {
        val diskText = "fun a() = 1\n"
        val file = myFixture.addFileToProject("discard/a.kt", diskText).virtualFile
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file)!!
        manager.saveDocument(document)
        val scope = scope("discard")
        val saved = load(scope)
        val stamp = file.modificationStamp
        WriteCommandAction.runWriteCommandAction(project) { document.setText("fun a() = 2\n") }
        assertTrue(manager.isFileModified(file))
        val edited = load(scope, saved)
        assertEquals(document.text, edited.files.single().text)
        // Repeated refreshes of unsaved text must also remain non-reusable.
        val repeated = load(scope, edited)
        manager.reloadFromDisk(document)
        assertFalse(manager.isFileModified(file))
        assertEquals(stamp, file.modificationStamp)
        val discarded = load(scope, repeated)
        assertEquals(diskText, discarded.files.single().text)
        assertSame(discarded.files.single(), load(scope, discarded).files.single())
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

    fun `test an entry file loads the project files it depends on, transitively`() {
        myFixture.addFileToProject("app/Main.java",
            "package app;\nimport app.util.Helper;\npublic class Main { void run() { Helper.help(); } }\n")
        myFixture.addFileToProject("app/util/Helper.java",
            "package app.util;\npublic class Helper { public static void help() { new Model(); } }\n")
        myFixture.addFileToProject("app/util/Model.java", "package app.util;\nclass Model extends Base {}\n")
        myFixture.addFileToProject("app/base/Base.java", "package app.base;\npublic class Base {}\n")
        myFixture.addFileToProject("app/util/Base.java", "package app.util;\nclass Base {}\n")
        myFixture.addFileToProject("app/other/Unrelated.java", "package app.other;\npublic class Unrelated { void u() { app.Main m = null; } }\n")

        val scope = scope("app/Main.java")
        assertTrue(scope.entry)
        assertEquals("app/Main.java and its dependencies", scope.displayName)
        val snapshot = load(scope)
        // Imports, static calls, constructor calls in the same package and inheritance; nothing that only uses the entry.
        assertEquals(listOf("app/Main.java", "app/util/Base.java", "app/util/Helper.java", "app/util/Model.java"),
            snapshot.files.map { it.path })
        assertEquals("app", snapshot.baseFolder)
        assertFalse(snapshot.truncated)

        val limited = load(scope, limits = CodeLimits(maxFiles = 2))
        // The nearest files are kept: the entry and what it uses directly.
        assertEquals(listOf("app/Main.java", "app/util/Helper.java"), limited.files.map { it.path })
        assertTrue(limited.truncated)
        // Model is found from Helper but not followed, so Base is never reached.
        assertEquals(1, limited.overLimit)
    }

    fun `test an entry file does not follow excluded files`() {
        myFixture.addFileToProject("walk/Entry.java", "package walk;\npublic class Entry { walk.gen.Out o; }\n")
        myFixture.addFileToProject("walk/gen/Out.java", "package walk.gen;\npublic class Out { walk.Hidden h; }\n")
        myFixture.addFileToProject("walk/Hidden.java", "package walk;\npublic class Hidden {}\n")
        val out = myFixture.findFileInTempDir("walk/gen")
        PsiTestUtil.addExcludedRoot(module, out)
        try {
            assertEquals(listOf("walk/Entry.java"), load(scope("walk/Entry.java")).files.map { it.path })
            assertThrows(IllegalArgumentException::class.java) { scope("walk/gen/Out.java") }
        } finally {
            PsiTestUtil.removeExcludedRoot(module, out)
        }
    }

    fun `test exclude patterns leave out files and folders of a folder scope`() {
        myFixture.addFileToProject("ex/src/a.kt", "fun a() = 1\n")
        myFixture.addFileToProject("ex/src/b.js", "var b = 1;\n")
        myFixture.addFileToProject("ex/web/c.js", "var c = 1;\n")
        myFixture.addFileToProject("ex/web/d.ts", "let d = 1;\n")
        myFixture.addFileToProject("ex/legacy/old.kt", "fun old() = 1\n")
        myFixture.addFileToProject("ex/legacy/deep/older.kt", "fun older() = 1\n")
        myFixture.addFileToProject("ex/test/t.kt", "fun t() = 1\n")

        val scope = scope("ex").copy(excludes = listOf("web/*.js", "legacy", "test/"))
        val snapshot = load(scope)
        assertEquals(listOf("ex/src/a.kt", "ex/src/b.js", "ex/web/d.ts"), snapshot.files.map { it.path })
        // web/c.js, and the legacy and test folders without their contents.
        assertEquals(3, snapshot.excludedCount)
        assertTrue(codeStatusText(snapshot).endsWith("· 3 excluded"))
        assertTrue("excluded_patterns" in codeOverview(snapshot, null, emptyList(), emptyList(), emptyList(), null))

        assertEquals(listOf("ex/legacy/deep/older.kt", "ex/legacy/old.kt", "ex/test/t.kt", "ex/web/d.ts"),
            load(scope("ex").copy(excludes = listOf("*.js", "src"))).files.map { it.path })
        // The chosen folder itself is never excluded.
        assertEquals(listOf("ex/test/t.kt"), load(scope("ex/test").copy(excludes = listOf("test/"))).files.map { it.path })
    }

    fun `test exclude patterns stop the dependency walk of an entry file`() {
        myFixture.addFileToProject("dep/Main.java", "package dep;\npublic class Main { dep.lib.Lib l; dep.Util u; }\n")
        myFixture.addFileToProject("dep/Util.java", "package dep;\npublic class Util {}\n")
        myFixture.addFileToProject("dep/lib/Lib.java", "package dep.lib;\npublic class Lib { dep.Deep d; }\n")
        myFixture.addFileToProject("dep/Deep.java", "package dep;\npublic class Deep {}\n")

        val scope = scope("dep/Main.java")
        assertEquals(listOf("dep/Deep.java", "dep/Main.java", "dep/Util.java", "dep/lib/Lib.java"), load(scope).files.map { it.path })
        val snapshot = load(scope.copy(excludes = listOf("lib/", "Util.java")))
        // Lib is not followed, so Deep, reached only through it, is left out too.
        assertEquals(listOf("dep/Main.java"), snapshot.files.map { it.path })
        assertEquals(2, snapshot.excludedCount)
        // The entry file is never excluded from its own scope.
        assertEquals(listOf("dep/Main.java"), load(scope.copy(excludes = listOf("*.java"))).files.map { it.path })
    }

    fun `test scope validation`() {
        myFixture.addFileToProject("valid/a.kt", "fun a() = 1\n")
        val directory = myFixture.findFileInTempDir("valid")
        assertEquals(scope("valid"), ReadAction.compute<CodeScope, Throwable> { codeScope(project, directory) })
        assertEquals(scope("valid"), scope(directory.path))
        assertEquals("", scope("").path)
        assertEquals("", scope(".").path)
        assertThrows(IllegalArgumentException::class.java) { scope("missing") }
        assertEquals(CodeScope("valid/a.kt", myFixture.findFileInTempDir("valid/a.kt").path, entry = true), scope("valid/a.kt"))
        assertEquals(scope("valid/a.kt"), scope(myFixture.findFileInTempDir("valid/a.kt").path))
        assertThrows(IllegalArgumentException::class.java) { scope("valid/../valid") }
    }

    private fun scope(path: String): CodeScope = ReadAction.compute<CodeScope, Throwable> { codeScope(project, path) }

    private fun binary() {
        val file = myFixture.tempDirFixture.createFile("scope/logo.png")
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
