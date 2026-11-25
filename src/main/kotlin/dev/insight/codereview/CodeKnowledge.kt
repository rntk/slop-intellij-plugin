package dev.insight.codereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.PsiManager
import dev.insight.agent.ToolRegistry
import dev.insight.agent.projectContextTools
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.configuredDecisions
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.AnchorKind
import dev.insight.knowledge.Claim
import dev.insight.knowledge.ClaimClassifier
import dev.insight.knowledge.DistillationWorkflow
import dev.insight.knowledge.DocInput
import dev.insight.knowledge.DriftCheckWorkflow
import dev.insight.knowledge.DriftKind
import dev.insight.knowledge.InMemoryClaimClassCache
import dev.insight.knowledge.KnowledgeBase
import dev.insight.knowledge.KnowledgeDocDiff
import dev.insight.knowledge.KnowledgeDocRenderer
import dev.insight.knowledge.KnowledgeOp
import dev.insight.knowledge.KnowledgeProgress
import dev.insight.knowledge.KnowledgeService
import dev.insight.knowledge.KnowledgeState
import dev.insight.knowledge.KnowledgeStoreException
import dev.insight.knowledge.RatchetGuard
import dev.insight.knowledge.RenderedDoc
import dev.insight.knowledge.RetireEvidence
import dev.insight.knowledge.Verdict
import dev.insight.knowledge.accepted
import dev.insight.knowledge.ide.CallEdgeRef
import dev.insight.knowledge.ide.DirectoryPartitioner
import dev.insight.knowledge.ide.ProjectClaimAnchorer
import dev.insight.knowledge.ide.ProjectPaths
import dev.insight.knowledge.ide.PsiAnchorResolver
import dev.insight.knowledge.ide.PsiEvidenceProvider
import dev.insight.knowledge.ide.SymbolLookup
import dev.insight.knowledge.ide.SymbolRef
import dev.insight.knowledge.ide.normalizeProjectPath
import dev.insight.knowledge.toMarkdown
import dev.insight.knowledge.ui.KnowledgeContent
import dev.insight.knowledge.ui.KnowledgeCounts
import dev.insight.knowledge.ui.KnowledgeReport
import dev.insight.knowledge.ui.ReportKind
import dev.insight.knowledge.ui.describeEvent
import dev.insight.knowledge.ui.describeFinding
import dev.insight.knowledge.ui.docTargetFor
import dev.insight.knowledge.ui.fenced
import dev.insight.knowledge.ui.fractionOf
import dev.insight.knowledge.ui.knowledgeCounts
import dev.insight.knowledge.ui.progressText
import dev.insight.llm.CompletionFn
import dev.insight.settings.ChatSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<CodeKnowledge>()

/** The most activity lines kept for the page. */
private const val MAX_ACTIVITY = 400

/**
 * The Knowledge view of a code review: runs the knowledge workflows (drift check, distillation, doc rendering) on the
 * project's [KnowledgeService], keeps what the page shows and handles its actions. Holds no Swing; [changed] is called on the
 * EDT whenever [content] has something new. [complete] replaces the configured model (tests); with it, decisions are off,
 * as in the Diff Review walkthrough. Only one run executes at a time per project, whichever tab started it.
 */
internal class CodeKnowledge(
    private val project: Project,
    private val scope: CoroutineScope,
    private val service: KnowledgeService,
    private val complete: CompletionFn?,
    /** The project-relative paths of the review's files, which Distill scope reads. */
    private val scopeFiles: () -> List<String>,
    private val openFile: (path: String, line: Int?) -> Unit,
    private val changed: () -> Unit,
) : Disposable {
    private var disposed = false
    private var job: Job? = null
    private var runId = 0
    /** The id of the run in progress, 0 when none; late updates of a finished run are dropped. */
    private var activeRun = 0
    private var progress: String? = null
    private var fraction: Double? = null
    private var status: String? = null
    private var warnings: List<String> = emptyList()
    private val activity = ArrayDeque<String>()
    private var reports: List<KnowledgeReport> = emptyList()
    private var selectedReport: ReportKind? = null
    private var reportSerial = 0
    private var docSelection: List<String> = emptyList()
    private var loadError: String? = null
    private var loading = false
    private var unsubscribe: (() -> Unit)? = null
    private var subscribedTo: KnowledgeBase? = null
    private var refreshQueued = false
    private var cachedState: KnowledgeState? = null
    private var cachedClaims = emptyList<Claim>()
    private val classes = InMemoryClaimClassCache()

    /** Loads the ledger in the background so the tile and the page can show counts; a damaged ledger is reported on the page. */
    fun load() {
        if (disposed || loading) return
        service.peek()?.let {
            subscribe(it)
            loadError = null
            return
        }
        loading = true
        scope.launch {
            try {
                subscribe(withContext(Dispatchers.IO) { service.base() })
                loadError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is KnowledgeStoreException) LOG.warn("Could not load the knowledge base", e) else LOG.info("Knowledge base unavailable: ${e.message}")
                loadError = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
                if (!disposed) changed()
            }
        }
    }

    /** The claims by lifecycle, for the tile; reads memory only. */
    fun counts(): KnowledgeCounts = knowledgeCounts(service.peek()?.snapshot() ?: KnowledgeState.EMPTY)

    /** What the page shows now; reads memory only. */
    fun content(): KnowledgeContent {
        val base = service.peek()
        val state = base?.snapshot() ?: KnowledgeState.EMPTY
        if (cachedState !== state) {
            cachedState = state
            cachedClaims = state.claims.values.toList()
        }
        val entries = base?.entries().orEmpty()
        return KnowledgeContent(
            counts = knowledgeCounts(state), claims = cachedClaims, entries = entries, revision = entries.lastOrNull()?.seq ?: 0,
            running = service.busy, progress = progress, fraction = fraction, status = status, warnings = warnings,
            activity = synchronized(activity) { activity.toList() }, reports = reports, selectedReport = selectedReport, reportSerial = reportSerial,
            docs = if (docSelection.isEmpty()) "flows/*.md" else "${docSelection.size} chosen ${if (docSelection.size == 1) "doc" else "docs"}",
            location = service.directory()?.toString(), error = loadError, canDistill = scopeFiles().isNotEmpty(),
        )
    }

    // ---- Runs -----------------------------------------------------------------------------------------------------

    private class Outcome(
        val status: String,
        val warnings: List<String> = emptyList(),
        val reports: List<KnowledgeReport> = emptyList(),
        val selected: ReportKind? = null,
    )

    /** Compares the statements of the chosen docs (the Markdown under flows by default) with the code; reports and marks, never edits. */
    fun checkDocs(): Job? = start("Checking docs") { base, progress ->
        val docs = readDocs()
        if (docs.isEmpty()) return@start Outcome("No docs to check. Add Markdown files under flows/ or choose docs.")
        val completion = completion().getOrNull()
        val resolver = PsiAnchorResolver(project)
        val report = DriftCheckWorkflow(base, ProjectClaimAnchorer(project, resolver), resolver, PsiEvidenceProvider(project),
            decisions(), completion).check(docs, progress) { record(describeFinding(it)) }
        val c = report.counts
        Outcome("Checked ${c.claims} statements in ${c.docs} ${if (c.docs == 1) "doc" else "docs"}: ${report.issues.size} to look at, " +
            "${c[DriftKind.OK]} ok.", report.warnings,
            listOf(KnowledgeReport(ReportKind.DRIFT, "Drift report", report.toMarkdown())), ReportKind.DRIFT)
    }

    /** Records what holds true about the review's files as claims, under the ratchet. */
    fun distill(): Job? = start("Distilling scope") { base, progress ->
        val files = scopeFiles()
        if (files.isEmpty()) return@start Outcome("There are no files in scope to distill.")
        val completion = completion().getOrElse {
            return@start Outcome("No assistant is configured (${it.message}). Set up a model in Settings, then distill the scope.")
        }
        val registry = ToolRegistry().register("locating, reading and searching current project files", projectContextTools(project))
        val result = DistillationWorkflow(base, DirectoryPartitioner(project), PsiEvidenceProvider(project), PsiAnchorResolver(project),
            completion, decisions(), registry = registry).run(files, progress) { event -> describeEvent(event)?.let(::record) }
        Outcome("Distilled ${result.distilled} of ${result.partitions} areas (${result.skipped} unchanged): ${result.submitted} ops, " +
            "${result.rejected} rejected by the ratchet.", result.warnings)
    }

    /** Writes a draft doc from the claims of [area] and compares it with the existing doc; nothing is written. */
    fun renderDoc(area: String): Job? = start("Rendering doc") { base, progress ->
        val state = base.snapshot()
        val target = docTargetFor(area, state.active(), project.name)
        progress.report("Rendering ${target.title}", 0, 1)
        val doc = KnowledgeDocRenderer(completion().getOrNull(), ClaimClassifier(decisions(), classes)).render(state, target)
        val existing = readText(doc.path)
        val diff = KnowledgeDocDiff.unified(existing, doc.markdown, doc.path)
        progress.report("Rendering ${target.title}", 1, 1)
        Outcome("Rendered a draft of ${doc.path} from ${doc.usedClaimIds.size} claims; nothing was written.", doc.warnings, listOf(
            KnowledgeReport(ReportKind.DRAFT, "Draft ${doc.path}", draftMarkdown(doc, existing != null), copyText = doc.markdown),
            KnowledgeReport(ReportKind.DIFF, "Diff ${doc.path}", diffMarkdown(doc.path, diff, existing != null)),
        ), ReportKind.DRAFT)
    }

    private fun start(title: String, body: suspend (KnowledgeBase, KnowledgeProgress) -> Outcome): Job? {
        if (disposed) return null
        if (service.busy) {
            status = "Another knowledge run is in progress. Stop it first."
            changed()
            return null
        }
        val id = ++runId
        activeRun = id
        progress = "Starting…"
        fraction = null
        status = null
        warnings = emptyList()
        synchronized(activity) { activity.clear() }
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            fun current() = !disposed && activeRun == id
            var outcome: Outcome? = null
            var note: String? = null
            try {
                val base = withContext(Dispatchers.IO) { service.base() }
                subscribe(base)
                outcome = withBackgroundProgress(project, title, cancellable = true) {
                    reportRawProgress { reporter ->
                        withContext(Dispatchers.Default) {
                            body(base, KnowledgeProgress { phase, done, total ->
                                val text = progressText(phase, done, total)
                                val part = fractionOf(done, total)
                                reporter.text(text)
                                reporter.fraction(part)
                                scope.launch { if (current()) { progress = text; fraction = part; changed() } }
                            })
                        }
                    }
                }
            } catch (e: CancellationException) {
                note = "$title stopped. What it recorded stays in the ledger."
                throw e
            } catch (e: Exception) {
                LOG.warn("$title failed", e)
                note = "$title failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    service.end(self)
                    if (current()) {
                        activeRun = 0
                        job = null
                        progress = null
                        fraction = null
                        status = outcome?.status ?: note
                        warnings = outcome?.warnings.orEmpty()
                        outcome?.let { done ->
                            val kinds = done.reports.map { it.kind }.toSet()
                            reports = (reports.filter { it.kind !in kinds } + done.reports).sortedBy { it.kind.ordinal }
                            done.selected?.let { selectedReport = it; reportSerial++ }
                        }
                    }
                    if (!disposed) changed()
                }
            }
        }
        if (!service.tryBegin(run)) {
            run.cancel()
            return null
        }
        job = run
        run.start()
        changed()
        return run
    }

    /** Stops the run in progress, whichever tab started it; what it recorded stays. */
    fun stop() = service.stop()

    // ---- The user's own ops ----------------------------------------------------------------------------------------

    /** Takes a claim out of the live set as the user. */
    fun retire(id: String) = submit(KnowledgeOp.Retire(id, "Retired by the user",
        RetireEvidence.ByUser("Retired in the Knowledge view")), "Retired $id")

    /** Makes a retired claim live again. */
    fun restore(id: String) = submit(KnowledgeOp.Restore(id), "Restored $id")

    private fun submit(op: KnowledgeOp, done: String) {
        if (disposed) return
        scope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { service.base().submit(op, RatchetGuard.USER) }
                status = if (entry.verdict.accepted) done else "Rejected: " + (entry.verdict as Verdict.Rejected).reason
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not record $op", e)
                status = "Could not record it: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!disposed) changed()
        }
    }

    // ---- Docs and navigation --------------------------------------------------------------------------------------

    /** Asks for the Markdown docs "Check docs" reads from now on. */
    fun chooseDocs() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withExtensionFilter("Markdown", "md", "markdown")
            .withTitle("Docs to Check")
            .withDescription("Hand-written Markdown docs whose statements are checked against the code")
        val picked = FileChooser.chooseFiles(descriptor, project, project.guessProjectDir()).toList()
        if (picked.isEmpty()) return
        scope.launch {
            docSelection = readAction { picked.map { ProjectPaths.relative(project, it) }.filter { !it.startsWith("/") } }.distinct()
            if (!disposed) changed()
        }
    }

    internal fun useDocs(paths: List<String>) { docSelection = paths.map(::normalizeProjectPath); changed() }

    /** Opens the code an anchor names, or says it could not be found. */
    fun openAnchor(anchor: Anchor) {
        scope.launch {
            when (anchor.kind) {
                AnchorKind.FILE -> openFile(anchor.ref, null)
                AnchorKind.DIRECTORY -> {
                    val directory = readAction { ProjectPaths.find(project, anchor.ref)?.let { PsiManager.getInstance(project).findDirectory(it) } }
                    if (directory != null) directory.navigate(true) else notFound(anchor.ref)
                }
                AnchorKind.SYMBOL, AnchorKind.ENTRYPOINT -> openSymbol(anchor.ref)
                AnchorKind.CALL_EDGE -> CallEdgeRef.parse(anchor.ref)?.let { openSymbol(it.caller) } ?: notFound(anchor.ref)
            }
        }
    }

    fun openCitation(path: String, line: Int?) = openFile(path, line)

    private suspend fun openSymbol(ref: String) {
        val place = smartReadAction(project) {
            SymbolRef.parse(ref)?.let { SymbolLookup.find(project, it).firstOrNull() }?.let { it.path to it.lines(project)?.first }
        }
        if (place != null) openFile(place.first, place.second) else notFound(ref)
    }

    private fun notFound(ref: String) {
        status = "$ref was not found in the current code."
        if (!disposed) changed()
    }

    private suspend fun readDocs(): List<DocInput> = readAction {
        val paths = docSelection.ifEmpty { flowDocs() }
        paths.mapNotNull { path ->
            ProjectPaths.find(project, path)?.takeIf { !it.isDirectory }?.let { DocInput(normalizeProjectPath(path), textOf(it)) }
        }.distinctBy { it.path }
    }

    private fun flowDocs(): List<String> = ProjectPaths.find(project, "flows")?.takeIf { it.isDirectory }?.children.orEmpty()
        .filter { !it.isDirectory && it.extension.equals("md", ignoreCase = true) }.sortedBy { it.name }.map { "flows/${it.name}" }

    private suspend fun readText(path: String): String? = readAction {
        ProjectPaths.find(project, path)?.takeIf { !it.isDirectory }?.let(::textOf)
    }

    private fun textOf(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file)

    // ---- Ports ----------------------------------------------------------------------------------------------------

    private suspend fun completion(): Result<CompletionFn> = complete?.let { Result.success(it) } ?: withContext(Dispatchers.IO) {
        try {
            Result.success<CompletionFn>(ChatSettings.getInstance().createClient()::complete)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    /** Decisions are off under a test-injected completion, like the walkthrough. */
    private suspend fun decisions(): DecisionFn? = if (complete == null) configuredDecisions() else null

    /** Adds a line to the activity from any thread; the page follows shortly. */
    private fun record(line: String) {
        synchronized(activity) {
            if (activity.size >= MAX_ACTIVITY) activity.removeFirst()
            activity.addLast(line)
        }
        scope.launch { queueRefresh() }
    }

    private fun subscribe(base: KnowledgeBase) {
        if (disposed || subscribedTo === base) return
        unsubscribe?.invoke()
        subscribedTo = base
        unsubscribe = base.addListener { _, _ -> scope.launch { queueRefresh() } }
    }

    /** Redraws shortly, once for a burst of ledger entries or activity lines. */
    private fun queueRefresh() {
        if (disposed || refreshQueued) return
        refreshQueued = true
        scope.launch {
            delay(REFRESH_MS)
            refreshQueued = false
            if (!disposed) changed()
        }
    }

    override fun dispose() {
        disposed = true
        job?.cancel()
        unsubscribe?.invoke()
        unsubscribe = null
        subscribedTo = null
    }

    internal companion object {
        const val REFRESH_MS = 150L

        fun draftMarkdown(doc: RenderedDoc, exists: Boolean): String = buildString {
            appendLine("> Draft for `${doc.path}`${if (exists) "" else " (a new doc)"}. Nothing was written to disk; use Copy draft to take it.")
            appendLine()
            append(doc.markdown.trimEnd())
            appendLine()
            if (doc.excluded.isNotEmpty()) {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("Left out of the draft: " + doc.excluded.groupingBy { it.reason }.eachCount().entries
                    .joinToString(", ") { (reason, n) -> "$n ${reason.name.lowercase().replace('_', ' ')}" } + ".")
            }
            if (doc.warnings.isNotEmpty()) {
                appendLine()
                appendLine("Warnings:")
                doc.warnings.forEach { appendLine("- $it") }
            }
        }

        fun diffMarkdown(path: String, diff: String, exists: Boolean): String = buildString {
            appendLine("# Diff for $path")
            appendLine()
            when {
                diff.isBlank() -> appendLine("The draft matches the existing doc.")
                else -> {
                    appendLine(if (exists) "What the draft would change in the existing doc. A proposal: the doc is not written." else
                        "The doc does not exist yet; this is what the draft would add. A proposal: nothing is written.")
                    appendLine()
                    append(fenced(diff, "diff"))
                }
            }
        }
    }
}
