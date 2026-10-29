package dev.insight.review.ui

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import dev.insight.review.ContextSummaryLauncher
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ContextSummaryVersion
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Targeted summary generation and version lifetime. Called on the EDT; contains no Swing state. */
internal class CanvasSummarySession(
    private val launcher: ContextSummaryLauncher?,
    private val currentTree: () -> ReviewSummaryTree?,
    private val changed: (ContextSummaryVersion?) -> Unit,
) {
    private val mutableVersions = mutableListOf<ContextSummaryVersion>()
    val versions: List<ContextSummaryVersion> get() = mutableVersions
    private var versionCount = 0
    private var disposed = false
    private class TopicRun(var latest: ReviewSummaries, var job: Job? = null)
    private var topicRun: TopicRun? = null
    var topicStatus: String? = null
        private set
    val topicsRunning: Boolean get() = topicRun != null
    val running: Boolean get() = topicsRunning || versions.any { it.running }

    fun retain(tree: ReviewSummaryTree) {
        versions.forEach { version ->
            if (version.summaries.tree !== tree) {
                if (version.running && version.summaries.tree.nodes.map { it.key } != tree.nodes.map { it.key }) {
                    version.running = false
                    version.job?.cancel()
                    version.error = "Source changed; resummarize with context again to update this version."
                }
                version.summaries = version.summaries.retainedIn(tree)
                version.request = version.request.copy(nodeIds = tree.summarizable.filter { it.identity in version.related }.mapTo(hashSetOf()) { it.id })
            }
        }
    }

    fun delete(version: ContextSummaryVersion) {
        mutableVersions.remove(version)
        version.running = false
        version.job?.cancel()
    }

    fun stopVersions() {
        versions.filter { it.running }.forEach {
            it.running = false
            it.job?.cancel()
        }
    }

    fun stopTopics() {
        val old = topicRun
        topicRun = null
        topicStatus = null
        old?.job?.cancel()
    }

    fun startVersion(current: ReviewSummaries, request: ContextSummaryRequest, select: (ContextSummaryVersion) -> Unit) {
        val launcher = launcher ?: return
        if (disposed || running) return
        val version = ContextSummaryVersion(++versionCount, request, current.without(request.nodeIds))
        mutableVersions += version
        select(version)
        changed(null)
        if (disposed || version !in versions || !version.running) return
        try {
            val job = launcher.launch(version.summaries, request, updated = { updated ->
                if (!disposed && version in versions && version.running) {
                    version.summaries = currentTree()?.let { tree ->
                        if (updated.tree === tree) updated else updated.retainedIn(tree)
                    } ?: updated
                    changed(version)
                }
            }, done = { result ->
                if (!disposed && version in versions && version.running) {
                    version.running = false
                    version.error = result.fold({ run ->
                        version.summaries = currentTree()?.let { tree ->
                            if (run.summaries.tree === tree) run.summaries else run.summaries.retainedIn(tree)
                        } ?: run.summaries
                        run.error ?: "Some summaries could not be rewritten. Run resummarization again to retry."
                            .takeIf { request.nodeIds.any { run.summaries.text(it) == null } }
                    }, { it.message ?: "Resummarizing stopped." })
                    changed(null)
                }
            })
            version.job = job
            if (disposed || version !in versions || !version.running) job.cancel()
        } catch (e: Exception) {
            if (e is CancellationException || e is ProcessCanceledException) {
                version.running = false
                throw e
            }
            LOG.warn("Could not start context summaries", e)
            if (disposed || version !in versions || !version.running) return
            version.running = false
            version.error = e.message ?: e.javaClass.simpleName
            changed(null)
        }
    }

    fun startTopics(start: ReviewSummaries, request: ContextSummaryRequest, save: (ReviewSummaries, Set<Int>) -> Unit) {
        val launcher = launcher ?: return
        if (disposed || running || request.nodeIds.isEmpty()) return
        val run = TopicRun(start)
        topicRun = run
        topicStatus = "${request.label}…"
        // Persist the empty starting scope before a launcher can synchronously publish its result.
        save(start, request.nodeIds)
        changed(null)
        if (disposed || topicRun !== run) return
        try {
            val job = launcher.launch(start, request, updated = {
                if (!disposed && topicRun === run) {
                    run.latest = it
                    save(it, request.nodeIds)
                }
            }, done = { result ->
                if (!disposed && topicRun === run) {
                    topicRun = null
                    topicStatus = result.fold({ completed ->
                        completed.error ?: "Some summaries could not be written. Run ${request.label} again to retry."
                            .takeIf { request.nodeIds.any { completed.summaries.text(it) == null } }
                    }, { it.message ?: "Summarizing stopped." })
                    save(result.getOrNull()?.summaries ?: run.latest, request.nodeIds)
                    changed(null)
                }
            })
            if (topicRun === run) run.job = job else job.cancel()
        } catch (e: Exception) {
            if (e is CancellationException || e is ProcessCanceledException) {
                if (topicRun === run) topicRun = null
                throw e
            }
            LOG.warn("Could not start topic summaries", e)
            if (disposed || topicRun !== run) return
            topicRun = null
            topicStatus = e.message ?: e.javaClass.simpleName
            changed(null)
        }
    }

    fun dispose() {
        disposed = true
        stopVersions()
        stopTopics()
    }

    private companion object {
        val LOG = logger<CanvasSummarySession>()
    }
}
