package dev.insight.knowledge.ide

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.AnchorKind
import dev.insight.knowledge.AnchorState
import kotlinx.coroutines.runBlocking

class PsiKnowledgeAdaptersTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver get() = PsiAnchorResolver(project)

    private fun resolve(kind: AnchorKind, ref: String): AnchorState = runBlocking { resolver.resolve(Anchor(kind, ref)) }

    private fun fingerprint(kind: AnchorKind, ref: String): String =
        (resolve(kind, ref) as AnchorState.Present).fingerprint!!

    private fun worker(body: String = "return 1;", comment: String = "", signature: String = "public int runJob(int n)") =
        """
        package a;
        /** Does work. */
        public class Worker {
            $comment
            $signature {
                $body
            }
            public void helper() {}
        }
        """.trimIndent()

    private fun kotlinModel(extra: String = "") = """
        package k.model

        /** A model. */
        class WalkModel(val id: Int) {
            fun step(n: Int): Int {
                $extra
                return n + id
            }
        }

        fun topLevel(x: Int) = x * 2
    """.trimIndent()

    fun `test resolves java class and method`() {
        myFixture.addFileToProject("a/Worker.java", worker())
        assertTrue(resolve(AnchorKind.SYMBOL, "a.Worker") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "a.Worker.runJob") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "Worker.java#Worker.helper") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.ENTRYPOINT, "Worker.runJob") is AnchorState.Present)
        assertEquals(AnchorState.Gone, resolve(AnchorKind.SYMBOL, "a.Worker.nothing"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.SYMBOL, "a.Other.runJob"))
    }

    fun `test resolves kotlin class function and top level function`() {
        myFixture.addFileToProject("k/WalkModel.kt", kotlinModel())
        assertTrue(resolve(AnchorKind.SYMBOL, "k.model.WalkModel") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "WalkModel.kt#WalkModel") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "WalkModel.step") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "k.model.topLevel") is AnchorState.Present)
    }

    fun `test method fingerprint ignores comments and formatting but not signatures or bodies`() {
        val file = myFixture.addFileToProject("a/Worker.java", worker())
        val before = fingerprint(AnchorKind.SYMBOL, "a.Worker.runJob")
        val classBefore = fingerprint(AnchorKind.SYMBOL, "a.Worker")

        replace(file, worker(comment = "// a note\n    /** more docs */", body = "// inside\n   return   1 ;"))
        assertEquals(before, fingerprint(AnchorKind.SYMBOL, "a.Worker.runJob"))
        assertEquals(classBefore, fingerprint(AnchorKind.SYMBOL, "a.Worker"))

        replace(file, worker(body = "return 2;"))
        assertFalse(before == fingerprint(AnchorKind.SYMBOL, "a.Worker.runJob"))
        assertEquals("a body edit leaves the class structure alone", classBefore, fingerprint(AnchorKind.SYMBOL, "a.Worker"))

        replace(file, worker(signature = "public long runJob(int n)"))
        assertFalse(before == fingerprint(AnchorKind.SYMBOL, "a.Worker.runJob"))
        assertFalse(classBefore == fingerprint(AnchorKind.SYMBOL, "a.Worker"))
    }

    fun `test kotlin fingerprint ignores comments`() {
        val file = myFixture.addFileToProject("k/WalkModel.kt", kotlinModel())
        val before = fingerprint(AnchorKind.SYMBOL, "k.model.WalkModel.step")
        replace(file, kotlinModel("// just a comment"))
        assertEquals(before, fingerprint(AnchorKind.SYMBOL, "k.model.WalkModel.step"))
        replace(file, kotlinModel("println(n)"))
        assertFalse(before == fingerprint(AnchorKind.SYMBOL, "k.model.WalkModel.step"))
    }

    fun `test file fingerprint is structural`() {
        val file = myFixture.addFileToProject("a/Worker.java", worker())
        val before = fingerprint(AnchorKind.FILE, "a/Worker.java")
        replace(file, worker(body = "// x\n return 5;", comment = "// c"))
        assertEquals("function bodies and comments are not structure", before, fingerprint(AnchorKind.FILE, "a/Worker.java"))
        replace(file, worker(signature = "public int runJob(int n, int m)"))
        assertFalse(before == fingerprint(AnchorKind.FILE, "a/Worker.java"))
    }

    fun `test text files fingerprint their normalized text`() {
        val file = myFixture.addFileToProject("docs/notes.md", "# Notes\n\nSome   text.\n")
        val before = fingerprint(AnchorKind.FILE, "docs/notes.md")
        replace(file, "# Notes\n\nSome text.\n\n")
        assertEquals(before, fingerprint(AnchorKind.FILE, "docs/notes.md"))
        replace(file, "# Notes\n\nOther text.\n")
        assertFalse(before == fingerprint(AnchorKind.FILE, "docs/notes.md"))
    }

    fun `test directory fingerprint follows its files`() {
        myFixture.addFileToProject("pkg/A.java", "package pkg; public class A { void a() {} }")
        val before = fingerprint(AnchorKind.DIRECTORY, "pkg")
        assertEquals(before, fingerprint(AnchorKind.DIRECTORY, "pkg/"))
        myFixture.addFileToProject("pkg/B.java", "package pkg; public class B {}")
        assertFalse(before == fingerprint(AnchorKind.DIRECTORY, "pkg"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.DIRECTORY, "nope"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.DIRECTORY, "pkg/A.java"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.FILE, "pkg"))
    }

    fun `test gone after deletion and moved when the file or package changes`() {
        val file = myFixture.addFileToProject("a/Worker.java", worker())
        assertTrue(resolve(AnchorKind.FILE, "a/Worker.java") is AnchorState.Present)
        assertTrue(resolve(AnchorKind.SYMBOL, "a/Worker.java#Worker") is AnchorState.Present)

        val moved = myFixture.addFileToProject("b/Worker.java", worker().replace("package a;", "package b;"))
        replace(file, "package a;\nclass Unrelated {}")
        val state = resolve(AnchorKind.SYMBOL, "a/Worker.java#Worker.runJob")
        assertTrue("expected Moved, got $state", state is AnchorState.Moved)
        assertEquals("b/Worker.java#Worker.runJob", (state as AnchorState.Moved).newRef)
        val byPackage = resolve(AnchorKind.SYMBOL, "a.Worker.runJob")
        assertEquals("b.Worker.runJob", (byPackage as AnchorState.Moved).newRef)

        runWriteActionAndWait { moved.virtualFile.delete(this); file.virtualFile.delete(this) }
        assertEquals(AnchorState.Gone, resolve(AnchorKind.SYMBOL, "Worker.runJob"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.FILE, "b/Worker.java"))
    }

    fun `test call edges are present while the call exists`() {
        val a = myFixture.addFileToProject(
            "c/Caller.java",
            "package c; public class Caller { public void start() { new Callee().finish(); } void idle() {} }",
        )
        myFixture.addFileToProject("c/Callee.java", "package c; public class Callee { public void finish() {} public void other() {} }")
        val edge = resolve(AnchorKind.CALL_EDGE, "c.Caller.start->c.Callee.finish")
        assertTrue("expected Present, got $edge", edge is AnchorState.Present)
        assertNotNull((edge as AnchorState.Present).fingerprint)
        assertEquals(AnchorState.Gone, resolve(AnchorKind.CALL_EDGE, "c.Caller.start->c.Callee.other"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.CALL_EDGE, "c.Caller.idle->c.Callee.finish"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.CALL_EDGE, "c.Caller.start->c.Missing.finish"))
        assertEquals(AnchorState.Gone, resolve(AnchorKind.CALL_EDGE, "garbage"))

        replace(a, "package c; public class Caller { public void start() { } void idle() {} }")
        assertEquals(AnchorState.Gone, resolve(AnchorKind.CALL_EDGE, "c.Caller.start->c.Callee.finish"))
    }

    fun `test anchorer extracts file links directory links and symbol mentions`() {
        myFixture.addFileToProject("a/Worker.java", worker())
        myFixture.addFileToProject("docs/design.md", "# Design")
        myFixture.addFileToProject("a/other/Z.java", "package a.other; public class Z {}")
        val text = """
            The [worker](../a/Worker.java) is driven by `Worker.runJob` and the notes in `a/other/` explain it.
            Missing: [gone](a/Nothing.java). See https://example.com for more.
        """.trimIndent()
        val anchors = runBlocking { ProjectClaimAnchorer(project).anchors(text, "docs/design.md") }
        val byKind = anchors.groupBy { it.kind }
        assertEquals(listOf("a/Worker.java"), byKind[AnchorKind.FILE]?.map { it.ref })
        assertEquals(listOf("a/other"), byKind[AnchorKind.DIRECTORY]?.map { it.ref })
        assertTrue(byKind[AnchorKind.SYMBOL].orEmpty().any { it.ref == "a.Worker.runJob" })
        assertTrue(anchors.all { it.fingerprint != null })
        assertEquals("anchors are deduplicated", anchors.size, anchors.distinct().size)
        assertTrue(anchors.none { it.ref.contains("Nothing") })
    }

    fun `test evidence respects the budget and carries locations`() {
        myFixture.addFileToProject("a/Worker.java", worker(body = "return 1;\n" + "int x = 0;\n".repeat(200)))
        myFixture.addFileToProject("a/Other.java", "package a; public class Other { void go() {} }")
        val anchors = listOf(
            Anchor(AnchorKind.SYMBOL, "a.Worker.runJob"),
            Anchor(AnchorKind.SYMBOL, "a.Worker"),
            Anchor(AnchorKind.FILE, "a/Worker.java"),
            Anchor(AnchorKind.DIRECTORY, "a"),
            Anchor(AnchorKind.SYMBOL, "a.Missing"),
        )
        val provider = PsiEvidenceProvider(project)
        val roomy = runBlocking { provider.evidence(anchors, 50_000) }
        assertEquals(anchors.size, roomy.size)
        val method = roomy.first()
        assertTrue(method.text.startsWith("public int runJob(int n)"))
        assertEquals("a/Worker.java", method.path)
        assertTrue(method.startLine != null && method.endLine!! > method.startLine!!)
        assertTrue(roomy[1].text.contains("helper"))
        assertTrue(roomy[2].text.contains("class Worker"))
        assertTrue(roomy[3].text.contains("Other.java"))
        assertTrue(roomy[4].text.contains("Not found"))

        val tight = runBlocking { provider.evidence(anchors, 200) }
        assertTrue("total ${tight.sumOf { it.text.length }}", tight.sumOf { it.text.length } <= 200)
        assertTrue(tight.size >= 4)
        assertTrue(runBlocking { provider.evidence(anchors, 0) }.isEmpty())
    }

    fun `test partitioner expands directories and skips generated ones`() {
        myFixture.addFileToProject("src/a/X.java", "class X {}")
        myFixture.addFileToProject("src/a/Y.java", "class Y {}")
        myFixture.addFileToProject("src/a/b/Z.java", "class Z {}")
        myFixture.addFileToProject("src/a/build/Gen.java", "class Gen {}")
        myFixture.addFileToProject("src/a/generated/Gen2.java", "class Gen2 {}")
        val parts = runBlocking { DirectoryPartitioner(project, maxFilesPerPartition = 1).partitions(listOf("src", "src/a/X.java", "missing")) }
        val summary = parts.map { Triple(it.area, it.files, it.parent) }
        assertEquals(
            listOf(
                Triple("src/a", listOf("src/a/X.java"), "src"),
                Triple("src/a", listOf("src/a/Y.java"), "src"),
                Triple("src/a/b", listOf("src/a/b/Z.java"), "src/a"),
            ),
            summary,
        )
    }

    fun `test partitioner skips knowledge ledger and keeps authored and ordinary siblings`() {
        myFixture.addFileToProject(".insight/knowledge/ledger.jsonl", "{\"secret\":true}")
        myFixture.addFileToProject(".insight/knowledge/Architecture.md", "## Authored knowledge")
        myFixture.addFileToProject("src/Ordinary.java", "class Ordinary {}")

        val files = runBlocking {
            DirectoryPartitioner(project).partitions(listOf(""))
        }.flatMap { it.files }.toSet()

        assertFalse(files.any { it.endsWith("ledger.jsonl") })
        assertTrue(files.any { it.endsWith("Architecture.md") })
        assertTrue(files.any { it.endsWith("Ordinary.java") })
    }

    fun `test project paths reject absolute and parent paths`() {
        val file = myFixture.addFileToProject("safe/Path.java", "class Path {} ").virtualFile
        assertNotNull(ProjectPaths.find(project, "safe/Path.java"))
        assertNull(ProjectPaths.find(project, "safe/../safe/Path.java"))
        assertNull(ProjectPaths.find(project, "/safe/Path.java"))
        assertNull(ProjectPaths.find(project, "C:\\safe\\Path.java"))
        assertEquals(file, ProjectPaths.findAbsolute(project, file.path))
    }

    fun `test containment rejects symlinks outside root`() {
        val temp = java.nio.file.Files.createTempDirectory("project-paths")
        try {
            val rootPath = java.nio.file.Files.createDirectory(temp.resolve("root"))
            val outside = java.nio.file.Files.writeString(temp.resolve("outside.txt"), "secret")
            val outsideDir = java.nio.file.Files.createDirectory(temp.resolve("outside-dir"))
            java.nio.file.Files.writeString(outsideDir.resolve("nested.txt"), "secret")
            java.nio.file.Files.writeString(rootPath.resolve("inside.txt"), "inside")
            java.nio.file.Files.createSymbolicLink(rootPath.resolve("escape.txt"), outside)
            java.nio.file.Files.createSymbolicLink(rootPath.resolve("escape-dir"), outsideDir)
            val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            val root = fs.refreshAndFindFileByNioFile(rootPath)!!
            val inside = fs.refreshAndFindFileByNioFile(rootPath.resolve("inside.txt"))!!
            val escape = fs.refreshAndFindFileByNioFile(rootPath.resolve("escape.txt"))!!
            val outsideFile = fs.refreshAndFindFileByNioFile(outside)!!
            val escapedDirectory = fs.refreshAndFindFileByNioFile(rootPath.resolve("escape-dir"))!!
            val escapedDescendant = fs.refreshAndFindFileByNioFile(rootPath.resolve("escape-dir/nested.txt"))!!
            assertTrue(ProjectPaths.contains(root, inside))
            assertFalse(ProjectPaths.contains(root, escape))
            assertFalse(ProjectPaths.contains(root, outsideFile))
            assertFalse(ProjectPaths.contains(root, escapedDirectory))
            assertFalse(ProjectPaths.contains(root, escapedDescendant))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private fun replace(file: com.intellij.psi.PsiFile, text: String) {
        com.intellij.openapi.application.runWriteActionAndWait {
            VfsUtil.saveText(file.virtualFile, text)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun runWriteActionAndWait(action: () -> Unit) = com.intellij.openapi.application.runWriteActionAndWait(action)
}
