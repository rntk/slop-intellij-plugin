package dev.insight.diff

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.Change

enum class ReviewDiffMode(val title: String, val usesWorkingTree: Boolean) {
    LOCAL("Uncommitted changes", true),
    WORKING_TREE("Revision → working tree", true),
    REVISIONS("Branches / commits", false),
    MERGE_BASE("Branch changes (merge base)", false);

    override fun toString() = title
}

/** User input. Git integrations resolve names once before loading either side of the snapshot. */
data class ReviewDiffRequest(
    val mode: ReviewDiffMode = ReviewDiffMode.LOCAL,
    val repository: String? = null,
    val base: String = "HEAD",
    val target: String = "HEAD",
) {
    fun validated(): ReviewDiffRequest {
        if (mode == ReviewDiffMode.LOCAL) return ReviewDiffRequest()
        require(!repository.isNullOrBlank()) { "Choose a Git repository." }
        fun ref(value: String, label: String): String {
            val result = value.trim()
            require(result.isNotEmpty() && result.length <= 200 && result.none { it.isISOControl() }) {
                "$label must be a branch, tag or commit (1–200 characters)."
            }
            return result
        }
        return copy(base = ref(base, "Base"), target = if (mode.usesWorkingTree) "HEAD" else ref(target, "Target"))
    }
}

data class ReviewComparison(
    val request: ReviewDiffRequest = ReviewDiffRequest(),
    val baseRevision: String? = null,
    val targetRevision: String? = null,
) {
    val beforeLabel: String get() = when (request.mode) {
        ReviewDiffMode.LOCAL -> "HEAD / VCS base"
        ReviewDiffMode.MERGE_BASE -> "merge-base(${request.base}, ${request.target}) · ${baseRevision?.take(12)}"
        else -> "${request.base} · ${baseRevision?.take(12)}"
    }
    val afterLabel: String get() = if (request.mode.usesWorkingTree) "Working tree" else "${request.target} · ${targetRevision?.take(12)}"
    val summary: String get() = if (request.mode == ReviewDiffMode.LOCAL) request.mode.title else "$beforeLabel → $afterLabel"
}

data class ReviewRepository(val path: String, val refs: List<String>) {
    override fun toString() = path
}

/** Prefer the active file's innermost root, then the project root, then a deterministic fallback. */
internal fun preferredReviewRepository(repositories: List<ReviewRepository>, activeFile: String?, projectPath: String?): String? {
    fun containing(path: String?) = path?.let { candidate ->
        repositories.filter { FileUtil.isAncestor(it.path, candidate, false) }.maxByOrNull { it.path.length }
    }
    return (containing(activeFile) ?: containing(projectPath) ?: repositories.firstOrNull())?.path
}

data class ReviewChanges(val changes: Collection<Change>, val comparison: ReviewComparison)

/** Optional VCS integration: the local review remains usable without the Git plugin. Methods run on IO. */
interface ReviewDiffProvider {
    suspend fun repositories(project: Project): List<ReviewRepository>
    fun changes(project: Project, request: ReviewDiffRequest): ReviewChanges?

    companion object {
        val EP_NAME = ExtensionPointName.create<ReviewDiffProvider>("dev.insight.reviewDiffProvider")
    }
}
