package dev.insight.callgraph.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import dev.insight.callgraph.DiffBaseProvider
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepositoryManager

/** Working tree against a Git revision in every repository of the project. Registered by `META-INF/diff-git.xml` only. */
class GitDiffBaseProvider : DiffBaseProvider {
    override fun changes(project: Project, ref: String): Collection<Change>? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) return null
        // Unlike getDiffWithWorkingTree, this throws VcsException for an unknown revision, so the panel can report it.
        return repositories.flatMap { repository ->
            GitChangeUtils.getDiffWithWorkingDir(project, repository.root, ref, null, false, true)
        }
    }
}
