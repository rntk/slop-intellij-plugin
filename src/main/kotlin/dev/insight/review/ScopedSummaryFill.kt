package dev.insight.review

import dev.insight.core.describeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Fills missing file and directory summaries in the ordinary review tree. Called on the EDT, where the launcher reports too. */
internal class ScopedSummaryFill(
    private val launcher: ContextSummaryLauncher,
    private val summaries: () -> ReviewSummaries,
    private val canRun: () -> Boolean,
    private val save: (updated: ReviewSummaries, ids: Set<Int>) -> Unit,
    private val changed: () -> Unit,
    private val failed: (Failure) -> Unit = {},
) {
    data class Target(val path: String, val kind: SummaryKind) {
        val label: String get() = when (kind) {
            SummaryKind.ROOT -> "./"
            SummaryKind.DIRECTORY -> "$path/"
            else -> path
        }

        fun includes(other: Target): Boolean = this == other ||
            kind == SummaryKind.ROOT || kind == SummaryKind.DIRECTORY && other.path.startsWith("$path/")
    }

    data class Failure(val targets: Set<Target>, val missingCount: Int, val reason: String)

    private class Request(val target: Target, val owned: Boolean)

    private val queued = LinkedHashSet<Request>()
    private var running: Set<Request> = emptySet()
    private var runningFiles: Set<String> = emptySet()
    private var job: Job? = null
    /** Counts runs, so the late reports of a cancelled run are ignored. */
    private var runs = 0

    val busy: Boolean get() = running.isNotEmpty() || queued.isNotEmpty()

    /** Missing file summaries being filled or waiting within the requested scopes. */
    val pendingFiles: Set<String> get() {
        val current = summaries()
        return runningFiles + queued.flatMap { request ->
            val target = request.target
            missingFiles(current, current.tree.scopeNode(target.path, target.kind))
        }
    }

    /** Missing non-topic root, directory, and file node IDs currently pending or queued in [current]. */
    fun pendingIds(current: ReviewSummaries = summaries()): Set<Int> {
        if (!busy) return emptySet()
        return current.tree.scopeNodes.asSequence()
            .filter { current.text(it.id) == null && isRequested(Target(it.path, it.kind)) }
            .mapTo(hashSetOf()) { it.id }
    }

    fun pending(path: String, kind: SummaryKind): Boolean {
        val target = target(path, kind)
        return isRequested(target) &&
            summaries().tree.scopeNode(target.path, target.kind) != null
    }

    private fun isRequested(target: Target): Boolean =
        running.any { it.target.includes(target) } || queued.any { it.target.includes(target) }

    fun canFill(path: String, kind: SummaryKind): Boolean = canRun() && !pending(path, kind) && missing(summaries(), target(path, kind)) != null

    /** Queues missing entries, coalescing overlapping scopes and serializing saves to the shared tree. */
    fun request(path: String, kind: SummaryKind): Boolean {
        if (pending(path, kind)) return true
        if (!canFill(path, kind)) return false
        val target = target(path, kind)
        queued.removeIf { target.includes(it.target) }
        enqueue(Request(target, owned = false))
        return true
    }

    /** Returns an EDT cancellation callback when this request adds new work. */
    fun requestOwned(path: String, kind: SummaryKind): (() -> Unit)? {
        if (!canFill(path, kind)) return null
        val request = Request(target(path, kind), owned = true)
        enqueue(request)
        return {
            if (request in running) {
                stopRunning()
                changed()
                resume()
            } else if (queued.remove(request)) {
                changed()
            }
        }
    }

    private fun enqueue(request: Request) {
        queued += request
        if (running.isEmpty()) resume() else changed()
    }

    /** Cancels the running fill and discards all requests from its review revision. */
    fun cancel() {
        if (!busy) return
        queued.clear()
        stopRunning()
        changed()
    }

    private fun stopRunning() {
        runs++
        val cancelled = job
        job = null
        running = emptySet()
        runningFiles = emptySet()
        cancelled?.cancel()
    }

    /** Starts work waiting for another fill or for the review to become available. */
    fun resume() {
        if (running.isNotEmpty() || queued.isEmpty() || !canRun()) return
        val start = summaries()
        val missingNodes = queued.mapNotNull { request -> missing(start, request.target)?.let { request to it } }.toMap()
        queued.retainAll(missingNodes.keys)
        val first = queued.firstOrNull()
        if (first == null) {
            changed()
            return
        }
        // An owned request gets its own job so it can be cancelled independently.
        val requests = if (first.owned) setOf(first) else queued.takeWhile { !it.owned }.toSet()
        queued.removeAll(requests)
        val nodes = requests.map { missingNodes.getValue(it) }
        val targets = requests.mapTo(linkedSetOf()) { it.target }
        val ids = nodes.flatMapTo(linkedSetOf()) { fillSummaryRequest(start, it).nodeIds }
        val run = ++runs
        running = requests
        runningFiles = nodes.flatMapTo(linkedSetOf()) { missingFiles(start, it) }
        fun finish(result: Result<ReviewSummaryRun>) {
            if (run != runs || running !== requests) return
            job = null
            running = emptySet()
            runningFiles = emptySet()
            val error = result.exceptionOrNull()
            try {
                changed()
                val current = summaries()
                val remaining = ids.count { current.text(it) == null }
                if (remaining > 0 && error !is CancellationException) {
                    val reason = error?.let(::describeFailure) ?: result.getOrNull()?.error ?: "The model returned incomplete summaries."
                    failed(Failure(targets.filterTo(linkedSetOf()) { missing(current, it) != null }, remaining, reason))
                }
            } finally {
                resume()
            }
        }
        val label = targets.joinToString(limit = 3) { it.label }
        val request = ContextSummaryRequest("", ids, label = "Summarize · $label", progressTitle = "Summarizing $label")
        val launched = try {
            changed()
            if (run != runs) return
            launcher.launch(start, request, updated = { updated ->
                if (run == runs && running === requests) { save(updated, ids); changed() }
            }, done = ::finish)
        } catch (e: Exception) {
            if (run != runs || running !== requests) throw e
            finish(Result.failure(e))
            if (e is CancellationException) throw e
            return
        }
        // A launcher may report before it returns, and the next run may have started meanwhile.
        if (run == runs && running === requests) job = launched else launched.cancel()
    }

    private fun target(path: String, kind: SummaryKind): Target =
        Target(path, normalizeSummaryKind(path, kind))

    private fun missing(summaries: ReviewSummaries, target: Target): SummaryNode? =
        summaries.tree.scopeNode(target.path, target.kind)?.takeIf { summaries.text(it.id) == null }

    private fun missingFiles(summaries: ReviewSummaries, scope: SummaryNode?): List<String> = scope?.files.orEmpty().filter { path ->
        summaries.tree.scopeNode(path, SummaryKind.FILE)?.let { summaries.text(it.id) == null } == true
    }
}
