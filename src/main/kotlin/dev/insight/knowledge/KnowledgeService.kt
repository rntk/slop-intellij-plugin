package dev.insight.knowledge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.configuredDecisions
import kotlinx.coroutines.Job
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the project's one [KnowledgeBase], stored under `<project base>/.insight/knowledge/` (see [FileKnowledgeStore]), and
 * the one slot a knowledge run (drift check, distillation, doc rendering) occupies, so only one runs at a time whichever
 * review tab started it. Holds no UI: views read [peek] and subscribe with [KnowledgeBase.addListener].
 *
 * The ratchet guard resolves the current Decisions configuration whenever it needs a meaning check, so opening a
 * project never touches the password safe.
 */
@Service(Service.Level.PROJECT)
class KnowledgeService(private val project: Project) : Disposable {
    @Volatile private var base: KnowledgeBase? = null
    @Volatile private var directoryOverride: Path? = null
    private val running = AtomicReference<Job?>(null)

    /** Where decisions come from; tests replace it. */
    @Volatile internal var decisions: suspend () -> DecisionFn? = { configuredDecisions() }

    /** The knowledge directory: [useDirectory]'s, else the project's, null for a project without a directory. */
    fun directory(): Path? = directoryOverride ?: project.basePath?.let { defaultKnowledgeDir(Path.of(it)) }

    /** Keeps the knowledge in [dir] (or back in the project's directory when null) from now on. Tests and special layouts. */
    @Synchronized internal fun useDirectory(dir: Path?) {
        directoryOverride = dir
        base = null
    }

    /** The knowledge base if it was loaded already; never reads files, so it is safe on the EDT. */
    fun peek(): KnowledgeBase? = base

    /**
     * The knowledge base, loading and replaying the ledger on first use (file IO: call off the EDT).
     * Throws [KnowledgeStoreException] for a damaged ledger and [IllegalStateException] for a project without a directory.
     */
    @Synchronized fun base(): KnowledgeBase {
        base?.let { return it }
        val dir = directory() ?: throw IllegalStateException("The project has no directory to keep knowledge in.")
        return KnowledgeBase(FileKnowledgeStore(dir), RatchetGuard(::decide)).also { base = it }
    }

    private suspend fun decide(request: DecisionRequest): DecisionResponse {
        val fn = decisions() ?: throw IllegalStateException("decisions are not configured")
        return fn(request)
    }

    /** Whether a run holds the slot. */
    val busy: Boolean get() = running.get()?.isActive == true

    /** Takes the slot for [job]; false while another run holds it. */
    fun tryBegin(job: Job): Boolean {
        val current = running.get()
        return (current == null || !current.isActive) && running.compareAndSet(current, job)
    }

    /** Frees the slot if [job] holds it. */
    fun end(job: Job) { running.compareAndSet(job, null) }

    /** Cancels the running run, whoever started it. What it already recorded stays in the ledger. */
    fun stop() { running.get()?.cancel() }

    override fun dispose() = stop()
}
