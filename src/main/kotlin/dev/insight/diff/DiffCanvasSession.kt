package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.canvas.CANVAS_LOAD_CHUNK
import dev.insight.canvas.CANVAS_ROOT
import dev.insight.canvas.CanvasPendingFile
import dev.insight.canvas.CanvasSession
import dev.insight.canvas.CanvasSessionController
import dev.insight.canvas.CanvasSessionFile
import dev.insight.canvas.CanvasSessionModel
import dev.insight.canvas.openCanvasSessionFile
import dev.insight.canvas.relativePath
import dev.insight.canvas.ConnectedFile
import dev.insight.canvas.FileConnection
import dev.insight.canvas.fileConnectionAnchor
import dev.insight.canvas.fileConnectionForest
import dev.insight.canvas.fileConnections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Walk both directions, opening only the changed files connected to the selected one. */
internal fun relatedDiffFiles(root: String, connections: List<FileConnection>): List<ConnectedFile> =
    fileConnectionForest(listOf(root), connections).drop(1)

internal data class DiffCanvasSession(val file: CanvasSessionFile, val unavailable: List<String>)

/** Changed files whose current source is the reviewed after-side, by review path; only these can give PSI evidence. */
internal suspend fun matchingDiffSources(project: Project, snapshot: ReviewSnapshot): Map<String, VirtualFile> =
    smartReadAction(project) {
        snapshot.files.mapNotNull { review ->
            if (review.status == "deleted" || review.after == null) return@mapNotNull null
            val file = LocalFileSystem.getInstance().findFileByPath(review.absolutePath) ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            if (psi.text != StringUtil.convertLineSeparators(review.after)) return@mapNotNull null
            review.path to file
        }.toMap()
    }

/** Changed files resolved whole in an exploration ([supported], by review path), and the relations between them. */
internal data class DiffFileRelations(val supported: Map<String, VirtualFile>, val connections: List<FileConnection>)

/**
 * Resolves every [eligible] changed file whole in [exploration] and collects the calls, references and inheritance
 * between them. Fails when reviewed source changed meanwhile; [view] names what is being prepared in that message.
 */
internal suspend fun relateDiffFiles(project: Project, snapshot: ReviewSnapshot, exploration: ProgressiveSchematic,
    eligible: Map<String, VirtualFile>, view: String): DiffFileRelations {
    val supported = linkedMapOf<String, VirtualFile>()
    for ((reviewPath, file) in eligible) {
        if (exploration.fileSchematic(file) != null) supported[reviewPath] = file
    }
    val byAbsolute = supported.entries.associate { it.value.path to it.key }
    val connections = smartReadAction(project) {
        for (review in snapshot.files.filter { it.path in supported }) {
            check(PsiManager.getInstance(project).findFile(supported.getValue(review.path))?.text ==
                StringUtil.convertLineSeparators(checkNotNull(review.after))) {
                "${review.path} changed while preparing the $view. Refresh the diff and retry."
            }
        }
        fileConnections(exploration.result, byAbsolute)
    }
    return DiffFileRelations(supported, connections)
}

/**
 * Builds a fresh exploration of this comparison, without touching another session's chats or cards. Only source
 * matching the after-side can contribute PSI evidence. Resolve every eligible changed file to find incoming links,
 * but open only the selected file's connected component; unchanged files never bridge two changed files.
 */
internal suspend fun prepareDiffCanvasSession(
    project: Project,
    snapshot: ReviewSnapshot,
    path: String,
    initialLimit: Int = CANVAS_LOAD_CHUNK,
): DiffCanvasSession {
    val selected = snapshot.files.firstOrNull { it.path == path } ?: error("$path is no longer in this diff.")
    val eligible = matchingDiffSources(project, snapshot)
    // Patches only where both sides were read; their new-side lines number the source the canvas shows.
    val diffs = snapshot.files.mapNotNull { review ->
        eligible[review.path]?.takeIf { review.before != null }?.let { it.path to review.patch }
    }.toMap()
    val source = eligible[selected.path]
        ?: error("${selected.path} has no current source matching the reviewed after-side. Refresh the diff or check out its target revision before opening it on canvas.")
    val opened = openCanvasSessionFile(project, source, store = null, saved = CanvasSession.new(
        relativePath(project, source.path), "${source.name} · ${snapshot.comparison.summary}"), diffs = diffs)
        ?: error("${selected.path} cannot be shown on canvas. Check that its language plugin is enabled.")
    val (supported, connections) = relateDiffFiles(project, snapshot, opened.exploration, eligible, "canvas")
    val model = CanvasSessionModel(opened.session)
    val controller = CanvasSessionController(project, source, opened.exploration, opened.root, model)
    val related = relatedDiffFiles(path, connections)
    val eager = related.take(initialLimit.coerceAtLeast(0))
    val deferred = related.drop(eager.size)
    try {
        controller.loadRoot()
        val cards = mutableMapOf<String, String>()
        for (entry in eager) {
            val parent = cards[entry.parent] ?: CANVAS_ROOT
            val shown = checkNotNull(controller.source(parent))
            val target = supported.getValue(entry.path)
            val anchor = fileConnectionAnchor(shown, target.path, entry.offset, controller::node)
            val card = controller.openFile(parent, anchor.startOffset, anchor.endOffset, target)
            cards[entry.path] = card.id
        }
        // Check source stamps again before handing this graph to the editor.
        opened.exploration.fileDeclarations(source)
        val pending = deferred.map { entry ->
            CanvasPendingFile(
                filePath = relativePath(project, supported.getValue(entry.path).path),
                parentPath = entry.parent?.let { supported[it]?.path }?.let { relativePath(project, it) },
                offset = entry.offset,
            )
        }
        withContext(Dispatchers.EDT) { opened.session = model.session.copy(pendingFiles = pending) }
    } finally {
        controller.dispose()
    }
    return DiffCanvasSession(opened, snapshot.files.filter { it.path !in supported }.map { it.path })
}
