package dev.insight.diff

import com.intellij.openapi.project.Project
import dev.insight.review.ReviewSymbols
import dev.insight.review.SymbolFile

/** A diff's after-side; deleted files have no current source. */
internal fun diffSymbols(project: Project, snapshot: ReviewSnapshot): ReviewSymbols = ReviewSymbols(
    project,
    snapshot.files.map { SymbolFile(it.path, it.absolutePath, it.after.takeUnless { _ -> it.status == "deleted" }) },
    stale = { path ->
        if (snapshot.comparison.request.mode.usesWorkingTree) "$path changed since the diff snapshot. Refresh before attaching a symbol."
        else "Current source for $path does not match the reviewed target revision. Use a file note without symbol."
    },
    missing = { "$it is not a file included in this diff" },
    findTool = "find_diff_symbols",
)
