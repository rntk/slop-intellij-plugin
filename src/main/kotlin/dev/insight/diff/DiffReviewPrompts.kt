package dev.insight.diff

import dev.insight.review.ChatFocus
import dev.insight.review.chatFocusPrompt

/**
 * The pinned focus for a turn on [files]: each file's patch; empty without a focus. A patch is not split by line, so a
 * focus on part of a file pins the whole file's diff and says so.
 */
internal fun diffFocusPrompt(files: List<ReviewFile>, focus: ChatFocus?): String {
    focus ?: return ""
    val byPath = files.associateBy { it.path }
    val whole = focus.lines?.takeIf { focus.files.isNotEmpty() }?.let {
        "\n\nThe file's diff covers the whole file; the focus is lines ${it.first}-${it.last} of it."
    }
    return "\n\n" + chatFocusPrompt(focus, "change", "read_diff_files") { path, _ -> byPath[path]?.patch } + whole.orEmpty()
}
