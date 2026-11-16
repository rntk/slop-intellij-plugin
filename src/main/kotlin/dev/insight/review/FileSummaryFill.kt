package dev.insight.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Writes the missing summaries of single files on request, such as a wall block asking for the summary it lacks. Each file is
 * filled through the ordinary summary pipeline ([fillSummaryRequest] on [launcher]), and what a run writes is saved into the
 * review's own Summaries tree, so the Summaries views, topics and later runs reuse it. One run at a time: files requested
 * meanwhile wait and go together in the next. Called on the EDT, where the launcher reports too.
 */
internal class FileSummaryFill(
    private val launcher: ContextSummaryLauncher,
    /** The review's current summaries; their tree holds the files that can be filled. */
    private val summaries: () -> ReviewSummaries,
    /** Whether a run may start now, for example not while the change refreshes. */
    private val canRun: () -> Boolean,
    /** Saves what a run has written so far, for the requested entries [ids], into the review. */
    private val save: (updated: ReviewSummaries, ids: Set<Int>) -> Unit,
    /** Called whenever [pending] changes or a run saved text. */
    private val changed: () -> Unit,
    /** Called with the paths and message of a run that failed or ended with an error. */
    private val failed: (paths: Set<String>, message: String) -> Unit = { _, _ -> },
) {
    private val queued = LinkedHashSet<String>()
    private var running: Set<String> = emptySet()
    private var job: Job? = null
    /** Counts runs, so the late reports of a cancelled run are ignored. */
    private var runs = 0

    /** The files being summarized or waiting for their run. */
    val pending: Set<String> get() = running + queued

    /** Whether [path] is a file of the review whose summary is missing, so [request] would fill it. */
    fun canFill(path: String): Boolean = summaries().let { current -> fileNode(current, path)?.let { current.text(it.id) == null } == true }

    /** Queues [path]'s summary; false when it cannot be filled now, true when it is pending. */
    fun request(path: String): Boolean {
        if (path in pending) return true
        if (!canFill(path) || !canRun()) return false
        queued += path
        if (running.isEmpty()) next() else changed()
        return true
    }

    /** Drops the waiting files and cancels the running fill. */
    fun cancel() {
        runs++
        queued.clear()
        job?.cancel()
        job = null
        running = emptySet()
        changed()
    }

    private fun next() {
        val start = summaries()
        val nodes = queued.mapNotNull { path -> fileNode(start, path)?.takeIf { start.text(it.id) == null }?.let { path to it } }.toMap()
        queued.clear()
        val ids = nodes.values.flatMapTo(linkedSetOf()) { fillSummaryRequest(start, it).nodeIds }
        if (ids.isEmpty() || !canRun()) {
            running = emptySet()
            changed()
            return
        }
        val paths = nodes.keys.toSet()
        val run = ++runs
        running = paths
        changed()
        val label = "Summarize · " + (paths.singleOrNull() ?: "${paths.size} files")
        val launched = launcher.launch(start, ContextSummaryRequest("", ids, label = label), updated = { updated ->
            if (run == runs) { save(updated, ids); changed() }
        }) { result ->
            if (run != runs) return@launch
            val failure = result.exceptionOrNull()
            // A cancelled run says nothing; the reader cancelled it.
            val error = if (failure is CancellationException) null
                else failure?.let { it.message ?: it.javaClass.simpleName } ?: result.getOrNull()?.error
            error?.let { failed(paths, it) }
            job = null
            running = emptySet()
            if (queued.isNotEmpty()) next() else changed()
        }
        // A launcher may report before it returns, and the next run may have started meanwhile.
        if (run == runs && running === paths) job = launched
    }

    private fun fileNode(summaries: ReviewSummaries, path: String): SummaryNode? =
        summaries.tree.nodes.firstOrNull { it.kind == SummaryKind.FILE && it.path == path }
}
