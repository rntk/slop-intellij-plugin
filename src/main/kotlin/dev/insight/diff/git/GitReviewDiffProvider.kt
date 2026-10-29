package dev.insight.diff.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.vcsUtil.VcsFileUtil
import com.intellij.vcsUtil.VcsUtil
import dev.insight.diff.ReviewChanges
import dev.insight.diff.ReviewComparison
import dev.insight.diff.ReviewDiffMode
import dev.insight.diff.ReviewDiffProvider
import dev.insight.diff.ReviewDiffRequest
import dev.insight.diff.ReviewRepository
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager

class GitReviewDiffProvider : ReviewDiffProvider {
    override suspend fun repositories(project: Project): List<ReviewRepository> {
        // Repository mappings initialize asynchronously; reading the cache immediately can return no roots.
        VcsRepositoryManager.getInstance(project).ensureUpToDate(true)
        return GitRepositoryManager.getInstance(project).repositories.sortedBy { it.root.path }.map { repository ->
            ReviewRepository(repository.root.path, (listOf("HEAD", "HEAD^") +
                repository.branches.localBranches.map { it.name } +
                repository.branches.remoteBranches.map { it.name }).distinct())
        }
    }

    override fun changes(project: Project, request: ReviewDiffRequest): ReviewChanges? {
        val root = GitRepositoryManager.getInstance(project).repositories
            .firstOrNull { it.root.path == request.repository }?.root ?: return null
        fun handler(command: GitCommand, vararg args: String): GitLineHandler {
            val handler = GitLineHandler(project, root, command)
            handler.setSilent(true)
            handler.addParameters(*args)
            return handler
        }
        fun command(command: GitCommand, vararg args: String): String =
            Git.getInstance().runCommand(handler(command, *args)).getOutputOrThrow().trim()
        fun resolve(ref: String): String = command(GitCommand.REV_PARSE, "--verify", "--end-of-options", "$ref^{commit}")
            .also { if (!it.matches(Regex("[0-9a-fA-F]{40,64}"))) throw VcsException("Cannot resolve commit: $ref") }
        val base = resolve(request.base)
        val target = if (request.mode.usesWorkingTree) null else resolve(request.target)
        val before = if (request.mode == ReviewDiffMode.MERGE_BASE) {
            val result = Git.getInstance().runCommand(handler(GitCommand.MERGE_BASE, base, target!!))
            if (result.exitCode == 1) throw VcsException("No common ancestor between ${request.base} and ${request.target}")
            result.getOutputOrThrow().trim()
        } else base
        val comparison = ReviewComparison(request, before, target)
        val changes = (if (request.mode.usesWorkingTree)
            GitChangeUtils.getDiffWithWorkingDir(project, root, before, null, false, true)
        else GitChangeUtils.getDiff(project, root, before, target, null)).toMutableList()
        if (request.mode.usesWorkingTree) {
            // Git sees disk content. A tracked file changed only in an unsaved editor must also be reviewed.
            val documents = FileDocumentManager.getInstance()
            val listed = changes.mapNotNullTo(hashSetOf()) { (it.afterRevision ?: it.beforeRevision)?.file?.path }
            val candidates = documents.unsavedDocuments.mapNotNull { document ->
                val file = documents.getFile(document) ?: return@mapNotNull null
                if (file.path in listed || ProjectLevelVcsManager.getInstance(project).getVcsRootFor(file) != root)
                    return@mapNotNull null
                val relative = VfsUtilCore.getRelativePath(file, root) ?: return@mapNotNull null
                relative to file
            }.toMap()
            // Batch the probes so a refresh doesn't launch two Git processes per unsaved editor.
            // Chunk only at the platform command-line limit, and keep filenames literal and NUL-delimited.
            for (paths in VcsFileUtil.chunkArguments(candidates.keys.toList())) {
                fun listedPaths(command: GitCommand, vararg args: String): Set<String> {
                    val probe = handler(command, *args, "--")
                    probe.addCustomEnvironmentVariable("GIT_LITERAL_PATHSPECS", "1")
                    probe.addParameters(paths)
                    return Git.getInstance().runCommand(probe).getOutputOrThrow().split('\u0000')
                        .filterTo(hashSetOf()) { it.isNotEmpty() }
                }
                val tracked = listedPaths(GitCommand.LS_FILES, "--cached", "-z")
                if (tracked.isEmpty()) continue // Untracked files remain opt-in, and ignored files stay out.
                val atBase = listedPaths(GitCommand.LS_TREE, "-r", "--name-only", "-z", before)
                for (relative in paths.filter { it in tracked }) {
                    val path = VcsUtil.getFilePath(candidates.getValue(relative))
                    val old = if (relative in atBase) GitContentRevision.createRevision(path, GitRevisionNumber(before), project) else null
                    changes += Change(old, CurrentContentRevision.create(path))
                }
            }
        }
        return ReviewChanges(changes, comparison)
    }
}
