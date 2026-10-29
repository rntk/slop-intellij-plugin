package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.git.GitReviewDiffProvider
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Exercises real Git refs, divergent branches and snapshot contents without changing checkout during review. */
class GitReviewDiffTest : BasePlatformTestCase() {
    fun `test branch commit merge base and working tree sources use the selected repository and exact sides`() {
        val directory = Files.createTempDirectory("insight-review-git")
        fun git(vararg args: String): String {
            val outputFile = Files.createTempFile("insight-review-git-output", ".log")
            try {
                val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args)
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start()
                try {
                    assertTrue("git ${args.joinToString(" ")} timed out", process.waitFor(10, TimeUnit.SECONDS))
                    val output = Files.readString(outputFile)
                    assertEquals(output, 0, process.exitValue())
                    return output.trim()
                } finally { if (process.isAlive) process.destroyForcibly() }
            } finally { Files.deleteIfExists(outputFile) }
        }
        fun write(path: String, text: String) { Files.writeString(directory.resolve(path), text) }
        val cleanPaths = listOf("clean.txt", "with space.txt", " leading.txt", "[draft].txt", ":notes.txt", "café.txt", "nested/nested-clean.txt")
        val vcs = ProjectLevelVcsManager.getInstance(project)
        val previousMappings = vcs.directoryMappings
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        try {
            git("init", "-b", "main")
            git("config", "user.email", "review-test@example.com")
            git("config", "user.name", "Review Test")
            git("config", "commit.gpgsign", "false")
            write("sample.txt", "base\n")
            Files.createDirectories(directory.resolve("nested"))
            cleanPaths.forEach { write(it, "clean base\n") }
            write(".gitignore", "ignored.txt\n")
            write("old.txt", "rename me\n")
            write("deleted.txt", "delete me\n")
            git("add", ".")
            git("commit", "-m", "base")
            val baseId = git("rev-parse", "HEAD")
            val unrelatedId = git("commit-tree", git("rev-parse", "$baseId^{tree}"), "-m", "unrelated history")
            git("branch", "unrelated", unrelatedId)
            git("checkout", "-b", "topic")
            write("sample.txt", "topic\n")
            write("added.txt", "new file\n")
            git("mv", "old.txt", "renamed.txt")
            git("rm", "deleted.txt")
            git("add", ".")
            git("commit", "-m", "topic")
            val topicId = git("rev-parse", "HEAD")
            git("checkout", "main")
            write("main-only.txt", "only on main\n")
            git("add", ".")
            git("commit", "-m", "main diverges")
            write("sample.txt", "working copy\n")
            write("untracked.txt", "untracked disk\n")
            write("ignored.txt", "ignored disk\n")
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            root.refresh(false, true)
            vcs.setDirectoryMappings(listOf(VcsDirectoryMapping(root.path, "Git")))
            val job = scope.launch {
                try {
                    val provider = GitReviewDiffProvider()
                    // Discovery must initialize mappings itself, including just-configured roots.
                    val repositories = withContext(Dispatchers.IO) { provider.repositories(project) }
                    assertTrue(repositories.single { it.path == root.path }.refs.containsAll(listOf("main", "topic")))
                    val loader = DiffReviewLoader(project)
                    val request = ReviewDiffRequest(ReviewDiffMode.REVISIONS, root.path, "main", "topic")
                    val direct = withContext(Dispatchers.IO) { loader.load(emptySet(), request) }
                    fun ReviewSnapshot.named(name: String) = files.single { it.path.substringAfterLast('/') == name }
                    assertEquals("base\n", direct.named("sample.txt").before)
                    assertEquals("topic\n", direct.named("sample.txt").after)
                    assertEquals("deleted", direct.named("main-only.txt").status)
                    assertEquals("added", direct.named("added.txt").status)
                    assertEquals("deleted", direct.named("deleted.txt").status)
                    assertEquals("renamed", direct.named("renamed.txt").status)
                    assertTrue(direct.named("renamed.txt").previousPath!!.endsWith("old.txt"))
                    assertEquals(topicId, direct.comparison.targetRevision)

                    val branch = withContext(Dispatchers.IO) { loader.load(emptySet(), request.copy(mode = ReviewDiffMode.MERGE_BASE)) }
                    assertEquals(baseId, branch.comparison.baseRevision)
                    assertFalse(branch.files.any { it.path.endsWith("main-only.txt") })
                    assertEquals("topic\n", branch.named("sample.txt").after)

                    try {
                        withContext(Dispatchers.IO) {
                            loader.load(emptySet(), request.copy(mode = ReviewDiffMode.MERGE_BASE, target = "unrelated"))
                        }
                        fail("Unrelated refs must report the missing common ancestor")
                    } catch (e: VcsException) {
                        assertEquals("No common ancestor between main and unrelated", e.message)
                    }

                    val commit = withContext(Dispatchers.IO) { loader.load(emptySet(), request.copy(base = "topic^", target = topicId)) }
                    assertEquals(baseId, commit.comparison.baseRevision)
                    assertEquals(branch.files.map { it.fingerprint }, commit.files.map { it.fingerprint })

                    val documents = (cleanPaths + listOf("untracked.txt", "ignored.txt")).map { name ->
                        FileDocumentManager.getInstance().getDocument(root.findFileByRelativePath(name)!!)!!
                    }
                    WriteCommandAction.runWriteCommandAction(project) { documents.forEach { it.setText("unsaved edit\n") } }
                    val working = withContext(Dispatchers.IO) {
                        loader.load(emptySet(), request.copy(mode = ReviewDiffMode.WORKING_TREE, base = "main"))
                    }
                    assertEquals("working copy\n", working.named("sample.txt").after)
                    cleanPaths.forEach { name ->
                        assertEquals(name, "clean base\n", working.named(name.substringAfterLast('/')).before)
                        assertEquals(name, "unsaved edit\n", working.named(name.substringAfterLast('/')).after)
                        assertEquals("clean base\n", Files.readString(directory.resolve(name)))
                    }
                    assertFalse(working.files.any { it.path.endsWith("/untracked.txt") || it.path.endsWith("/ignored.txt") })
                    // Subsequent refreshes must observe both editor edits and a ref moving to another commit.
                    WriteCommandAction.runWriteCommandAction(project) { documents.first().setText("another edit\n") }
                    git("branch", "-f", "review-base", "main")
                    val refreshRequest = request.copy(mode = ReviewDiffMode.WORKING_TREE, base = "review-base")
                    val refreshed = withContext(Dispatchers.IO) { loader.load(emptySet(), refreshRequest) }
                    assertEquals("another edit\n", refreshed.named("clean.txt").after)
                    git("branch", "-f", "review-base", "topic")
                    val moved = withContext(Dispatchers.IO) { loader.load(emptySet(), refreshRequest) }
                    assertEquals(topicId, moved.comparison.baseRevision)
                    assertEquals("topic\n", moved.named("sample.txt").before)
                    try {
                        withContext(Dispatchers.IO) { loader.load(emptySet(), request.copy(base = "missing-ref")) }
                        fail("Unknown refs must fail instead of falling back to local changes")
                    } catch (_: VcsException) { }
                    assertEquals("main", git("symbolic-ref", "--short", "HEAD"))
                } catch (e: Throwable) { failure = e }
            }
            PlatformTestUtil.waitWithEventsDispatching("Git snapshots should load", { job.isCompleted }, 30 /* seconds */)
            failure?.let { throw it }
        } finally {
            scope.cancel()
            vcs.setDirectoryMappings(previousMappings)
            directory.toFile().deleteRecursively()
            // The light project is shared with later tests; discard this repository's cached changes.
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
            var updated = false
            ChangeListManager.getInstance(project).invokeAfterUpdate(true) { updated = true }
            PlatformTestUtil.waitWithEventsDispatching("Git fixture changes should be cleared", { updated }, 10)
        }
    }
}
